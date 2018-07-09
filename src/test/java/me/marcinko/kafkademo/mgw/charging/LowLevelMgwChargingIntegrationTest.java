package me.marcinko.kafkademo.mgw.charging;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import me.marcinko.kafkademo.utils.EmbeddedSingleNodeKafkaCluster;
import me.marcinko.kafkademo.utils.IntegrationTestUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hr.kapsch.mgw.charging.ChargingRequestType;
import hr.kapsch.mgw.charging.avro.ChargingContext;
import hr.kapsch.mgw.charging.avro.ChargingParty;
import hr.kapsch.mgw.charging.avro.ChargingRequest;
import hr.kapsch.mgw.charging.avro.ChargingRequestData;
import hr.kapsch.mgw.domain.Direction;

public class LowLevelMgwChargingIntegrationTest {
	private final Logger logger = LoggerFactory.getLogger(LowLevelMgwChargingIntegrationTest.class);

	@ClassRule
	public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

	private static String inputTopic = "inputTopic";
	private static String outputTopic = "outputTopic";

	@BeforeClass
	public static void startKafkaCluster() throws Exception {
		CLUSTER.createTopic(inputTopic);
		CLUSTER.createTopic(outputTopic);
	}

	@Test
	public void shouldRoundTripSpecificAvroDataThroughKafka() throws Exception {
		produceTopicValues();

		final ReservedChargingTransactionStore reservedChargingTransactionStore = new InMemoryReservedChargingTransactionStoreImpl();

		final Set<Long> allowedPartnerIds = constructPartnerIds();
		final Map<String, Boolean> prepaidPostpaidRegistry = constructSubscriberPrepaidPostpaidRegistry();

		consumeTopicValues(reservedChargingTransactionStore, allowedPartnerIds, prepaidPostpaidRegistry);
		consumeTopicValues(reservedChargingTransactionStore, allowedPartnerIds, prepaidPostpaidRegistry);
	}

	private void consumeTopicValues(ReservedChargingTransactionStore reservedChargingTransactionStore, Set<Long> allowedPartnerIds, Map<String, Boolean> prepaidPostpaidRegistry) throws InterruptedException {
		final KafkaConsumer consumer = constructConsumer();

		final List<ChargingRequestData> allPolledChargingRequests = pollMgwMessages(consumer);
		final List<ChargingRequestData> relevantPolledChargingRequests = allPolledChargingRequests.stream()
				.filter(chargingRequestData -> isRequestOfInterest(allowedPartnerIds, chargingRequestData))
				.collect(Collectors.toList());

		// all reserved transactions in this poll
		final Set<ChargingTransaction> polledReservedTransactions = relevantPolledChargingRequests.stream()
				.filter(requestData -> requestData.getRequest().getType().equals(ChargingRequestType.RESERVE))
				.map(requestData -> constructTransaction(prepaidPostpaidRegistry, requestData))
				.collect(Collectors.toSet());

		// IDs of all charged transactions in this poll
		final Set<String> chargedTransactionIds = relevantPolledChargingRequests.stream()
				.filter(requestData -> requestData.getRequest().getType().equals(ChargingRequestType.CHARGE))
				.map(requestData -> requestData.getRequest().getReservationId())
				.collect(Collectors.toSet());

		// all polled reserved transactions not being charged in this poll, so we should store these for later charging
		final Set<ChargingTransaction> nonStoredReservedTransactions = polledReservedTransactions.stream()
				.filter(reservedTransaction -> !chargedTransactionIds.contains(reservedTransaction.getReservationId()))
				.collect(Collectors.toSet());

		if (!nonStoredReservedTransactions.isEmpty()) {
			reservedChargingTransactionStore.store(nonStoredReservedTransactions);
		}

		final Set<ChargingTransaction> nonStoredChargedTransactions = polledReservedTransactions.stream()
				.filter(reservedTransaction -> chargedTransactionIds.contains(reservedTransaction.getReservationId()))
				.collect(Collectors.toSet());

		final Set<String> storedChargedTransactionIds = calculateStoredChargedTransactionIds(chargedTransactionIds, nonStoredChargedTransactions);
		final Set<ChargingTransaction> storedChargedTransactions = findAndDeleteStoredReservedTransactionByIds(reservedChargingTransactionStore, storedChargedTransactionIds);

		final Set<ChargingTransaction> chargedTransactions = union(nonStoredChargedTransactions, storedChargedTransactions);

		System.out.println("### chargedTransactions = " + chargedTransactions);

//		consumer.commitSync();
		consumer.close();
	}

	private Set<ChargingTransaction> findAndDeleteStoredReservedTransactionByIds(ReservedChargingTransactionStore reservedChargingTransactionStore, Set<String> storedChargedTransactionIds) {
		if (storedChargedTransactionIds.isEmpty()) {
			return Collections.emptySet();
		}
		else {
			final Set<ChargingTransaction> storedChargedTransactions = new HashSet<>(reservedChargingTransactionStore.findByIdIn(storedChargedTransactionIds));
			reservedChargingTransactionStore.deletByIdIn(storedChargedTransactionIds);
			return storedChargedTransactions;
		}
	}

	private KafkaConsumer constructConsumer() throws InterruptedException {
		Properties consumerConfig = new Properties();
		consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "specific-avro-integration-test-standard-consumer");
		consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
		consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
		consumerConfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CLUSTER.schemaRegistryUrl());
		consumerConfig.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

		Thread.sleep(3000L);

		KafkaConsumer consumer = new KafkaConsumer<>(consumerConfig);
		consumer.subscribe(Collections.singletonList(inputTopic));
		return consumer;
	}

	private List<ChargingRequestData> constructInputValues() {
		List<ChargingRequestData> list = new ArrayList<>();
		list.add(constructReserveRequest("rId1", 111L, true, "385912392624", 123.4, "billTxt1", Instant.now()));
		list.add(constructReserveRequest("rId2", 111L, true, "385912392625", 101.4, "billTxt2", Instant.now()));
		list.add(constructChargeRequest("rId2", 111L, true, Instant.now()));
		return list;
	}

	private ChargingRequestData constructChargeRequest(String reservationId, long partnerId, boolean success, Instant receivedInsant) {
		long time = receivedInsant.toEpochMilli();
		final ChargingRequest request = new ChargingRequest(UUID.randomUUID().toString(), reservationId, time, ChargingRequestType.CHARGE, null, null, null, null, true, 321L, ChargingParty.END_USER, Direction.SEND, null, null, null, null, null, "SOmeBssCode", null, null, null, null, null, null, null);
		final ChargingContext context = new ChargingContext(reservationId, partnerId, 123L, 223L, 334L, 123L, success, null, false, null);
		return new ChargingRequestData(request, context);
	}

	private void produceTopicValues() throws java.util.concurrent.ExecutionException, InterruptedException {
		//
		// Step 2: Produce some input data to the input topic.
		//
		Properties producerConfig = new Properties();
		producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
		producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
		producerConfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CLUSTER.schemaRegistryUrl());

		List<ChargingRequestData> inputValues = constructInputValues();

		IntegrationTestUtils.produceValuesSynchronously(inputTopic, inputValues, producerConfig);
	}

	private <T> Set<T> union(Set<T> set1, Set<T> set2) {
		Set<T> set = new HashSet<>(set1);
		set.addAll(set2);
		return set;
	}

	private Set<String> calculateStoredChargedTransactionIds(Set<String> chargedTransactionIds, Set<ChargingTransaction> nonStoredChargedTransactions) {
		final Set<String> nonStoredChargedTransactionIds = nonStoredChargedTransactions.stream().map(ChargingTransaction::getReservationId).collect(Collectors.toSet());

		// these tx IDs should point to some transactions in local stores
		final Set<String> storedChargedTransactionIds = new HashSet<>(chargedTransactionIds);
		storedChargedTransactionIds.removeAll(nonStoredChargedTransactionIds);
		return storedChargedTransactionIds;
	}

	private ChargingTransaction constructTransaction(Map<String, Boolean> prepaidPostpaidRegistry, ChargingRequestData requestData) {
		final String reservationId = requestData.getRequest().getReservationId();
		final LocalDate localDate = Instant.ofEpochMilli(requestData.getRequest().getReceived()).atZone(ZoneId.systemDefault()).toLocalDate();
		final Long partnerId = requestData.getContext().getPartnerId();
		final String billingText = requestData.getRequest().getBillingText();
		final ChargingSubscriberType chargingSubscriberType = resolveChargingCountType(requestData.getRequest().getEndUserAddress(), prepaidPostpaidRegistry);
		return new ChargingTransaction(reservationId, localDate, partnerId, billingText, chargingSubscriberType);
	}

	private boolean isRequestOfInterest(Set<Long> allowedPartnerIds, ChargingRequestData chargingRequestData) {
		return allowedPartnerIds.contains(chargingRequestData.getContext().getPartnerId()) && chargingRequestData.getContext().getSuccess();
	}

	private static ChargingSubscriberType resolveChargingCountType(String msisdn, Map<String, Boolean> prepaidPostpaidRegistry) {
		Boolean prepaid = prepaidPostpaidRegistry.get(msisdn);
		if (prepaid == null) {
			return ChargingSubscriberType.UNKNOWN;
		}
		else if (prepaid.equals(Boolean.TRUE)) {
			return ChargingSubscriberType.PREPAID;
		}
		else {
			return ChargingSubscriberType.POSTPAID;
		}
	}

	private List<ChargingRequestData> pollMgwMessages(KafkaConsumer consumer) {
		ConsumerRecords<?, ChargingRequestData> consumerRecords = consumer.poll(10000L);
		List<ChargingRequestData> list = new ArrayList<>();
		for (ConsumerRecord<?, ChargingRequestData> consumerRecord : consumerRecords) {
			list.add(consumerRecord.value());
		}
		return list;
	}

	private ChargingRequestData constructReserveRequest(String reservationId, Long partnerId, boolean success, String endUserAddress, Double amount, String billingText, Instant receivedInstant) {
		long time = receivedInstant.toEpochMilli();
		final ChargingRequest request = new ChargingRequest(UUID.randomUUID().toString(), reservationId, time, ChargingRequestType.RESERVE, endUserAddress, amount, billingText, null, true, 321L, ChargingParty.END_USER, Direction.SEND, null, null, null, null, null, "SOmeBssCode", null, null, null, null, null, null, null);
		final ChargingContext context = new ChargingContext(reservationId, partnerId, 123L, 223L, 334L, 123L, success, null, false, null);
		return new ChargingRequestData(request, context);
	}

	private static Set<Long> constructPartnerIds() {
		final Set<Long> set = new HashSet<>();
		set.add(111L);
		set.add(222L);
		return set;
	}


	private static Map<String, Boolean> constructSubscriberPrepaidPostpaidRegistry() {
		final Map<String, Boolean> map = new HashMap<>();
		map.put("385912392624", true);
		map.put("385912392625", false);
		map.put("385912392626", false);
		map.put("385912392627", false);
		map.put("385912392628", true);
		map.put("385912392629", false);
		return map;
	}
}
