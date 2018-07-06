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
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import me.marcinko.kafkademo.mgw.message.MessageCount;
import me.marcinko.kafkademo.mgw.message.MessageCountType;
import me.marcinko.kafkademo.mgw.message.RoamingInterval;
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

import hr.kapsch.mgw.charging.ChargingRequestType;
import hr.kapsch.mgw.charging.avro.ChargingContext;
import hr.kapsch.mgw.charging.avro.ChargingParty;
import hr.kapsch.mgw.charging.avro.ChargingRequest;
import hr.kapsch.mgw.charging.avro.ChargingRequestData;
import hr.kapsch.mgw.domain.Direction;
import hr.kapsch.mgw.messaging.message.avro.MessageContentType;
import hr.kapsch.mgw.messaging.message.avro.MessageData;

public class LowLevelMgwChargingIntegrationTest {
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
		//
		// Step 1: Configure and start the processor topology.
		//

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

		//
		// Step 3: Verify the application's output data.
		//
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

		final Set<Long> allowedPartnerIds = constructPartnerIds();
		final Map<String, Boolean> prepaidPostpaidRegistry = constructSubscriberPrepaidPostpaidRegistry();

		final List<MessageData> list = pollMgwMessages(consumer);
		final List<MessageCount> messageCounts = list.stream()
				.filter(messageData -> isMessageDataOfInterest(allowedPartnerIds, messageData))
				.map(messageData -> {
					final LocalDate localDate = Instant.ofEpochMilli(messageData.getMessage().getReceived()).atZone(ZoneId.systemDefault()).toLocalDate();
					final Long partnerId = messageData.getContext().getPartnerId();
					final boolean mms = messageData.getMessage().getContentTypeIn().equals(MessageContentType.MMS);
					final String shortCode = messageData.getContext().getBssCode();
					final long count = messageData.getMessage().getDirection().equals(Direction.SEND) ? messageData.getContext().getOutgoingCnt() : messageData.getContext().getIncomingCnt();
					final MessageCountType messageCountType = resolveSubscriberCountType(messageData.getMessage().getDest(), prepaidPostpaidRegistry);
					return new MessageCount(localDate, partnerId, mms, shortCode, messageCountType, count);
				})
				.collect(Collectors.toList());

		System.out.println("### messageCounts = " + messageCounts);

		consumer.close();

//		assertEquals(inputValues, actualValues);
	}

	private static MessageCountType resolveSubscriberCountType(String msisdn, Map<String, Boolean> prepaidPostpaidRegistry) {
		if (prepaidPostpaidRegistry.containsKey(msisdn)) {
			return MessageCountType.MT_TMOBILE;
		}
		else if (msisdn.startsWith("385")) {
			return MessageCountType.MT_DOMESTIC;
		}
		else {
			return MessageCountType.MT_FOREIGN;
		}
	}

	private static boolean isSubscriberInRoaming(String msisdn, Instant receivedInstant, Map<String, List<RoamingInterval>> roamingIntervals) {
		final List<RoamingInterval> subscriberRoamingIntervals = roamingIntervals.get(msisdn);
		if (subscriberRoamingIntervals == null) {
			return false;
		}
		else {
			final Optional<RoamingInterval> matchedInterval = subscriberRoamingIntervals.stream().filter(roamingInterval -> receivedInstant.isBefore(roamingInterval.getTimeTo()) && !receivedInstant.isBefore(roamingInterval.getTimeTo())).findFirst();
			return matchedInterval.map(RoamingInterval::isInRoaming).orElse(false);
		}
	}

	private List<MessageData> pollMgwMessages(KafkaConsumer consumer) {
		ConsumerRecords<?, MessageData> consumerRecords = consumer.poll(10000L);
		List<MessageData> list = new ArrayList<>();
		for (ConsumerRecord<?, MessageData> consumerRecord : consumerRecords) {
			list.add(consumerRecord.value());
		}
		return list;
	}

	private List<ChargingRequestData> constructInputValues() {
		List<ChargingRequestData> list = new ArrayList<>();
		list.add(constructReserveRequest("rId1", 111L, true, "385912392624", 123.4, "billTxt1", Instant.now()));
		return list;
	}

	private ChargingRequestData constructReserveRequest(String reservationId, Long partnerId, boolean success, String endUserAddress, Double amount, String billingText, Instant receivedInstant) {
		long time = receivedInstant.toEpochMilli();
		final ChargingRequest request = new ChargingRequest(UUID.randomUUID().toString(), reservationId, time, ChargingRequestType.RESERVE, endUserAddress, amount, billingText, null, true, 321L, ChargingParty.END_USER, Direction.SEND, null, null, null, null, null, "SOmeBssCode", null, null, null, null, null, null, null);
		final ChargingContext context = new ChargingContext(reservationId, partnerId, 123L, 223L, 334L, 123L, success, null, false, null);
		return new ChargingRequestData(request, context);
	}

	private boolean isMessageDataOfInterest(Set<Long> allowedPartnerIds, MessageData value) {
		return allowedPartnerIds.contains(value.getContext().getPartnerId()) && value.getContext().getBssCode() != null;
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
