package me.marcinko.kafkademo.mgw.message;

import java.nio.ByteBuffer;
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

import com.google.common.collect.Lists;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import me.marcinko.kafkademo.utils.EmbeddedSingleNodeKafkaCluster;
import me.marcinko.kafkademo.utils.IntegrationTestUtils;
import me.marcinko.kafkademo.utils.PollResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import hr.kapsch.mgw.domain.Direction;
import hr.kapsch.mgw.messaging.message.avro.ImplicitChargingResult;
import hr.kapsch.mgw.messaging.message.avro.Message;
import hr.kapsch.mgw.messaging.message.avro.MessageContentType;
import hr.kapsch.mgw.messaging.message.avro.MessageData;
import hr.kapsch.mgw.messaging.message.avro.MessageDeliveryStatus;
import hr.kapsch.mgw.messaging.message.avro.MessagingContext;
import hr.kapsch.mgw.messaging.message.avro.RoutingInfo;

public class LowLevelMgwMessageIntegrationTest {
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
		produceInputValues();

		final KafkaConsumer consumer = constructConsumer();
		consumeKafkaRecords(consumer);
		consumer.close();

//		assertEquals(inputValues, actualValues);
	}

	private void consumeKafkaRecords(KafkaConsumer consumer) throws InterruptedException {
		System.out.println("### Starting consuming task");

		final Set<Long> allowedPartnerIds = fetchPartnerIds();
		final Map<String, Boolean> prepaidPostpaidRegistry = constructSubscriberPrepaidPostpaidRegistry();
		final Map<String, List<RoamingInterval>> roamingIntervals = constructSubscriberRoamingIntervals();

		PollResult<MessageData> pollResult = null;

		boolean resetted = true;
		while (!(pollResult = pollMgwMessages(consumer)).getValues().isEmpty()) {
			final List<MessageData> values = pollResult.getValues();
			System.out.println("### pollResult = " + pollResult);
			System.out.println("### list (" + values.size() + ") = " + values);
			final List<MessageCount> messageCounts = constructMessageCounts(values, allowedPartnerIds, prepaidPostpaidRegistry, roamingIntervals);
			System.out.println("### messageCounts = " + messageCounts);

			consumer.commitSync();

			if (!resetted) {
				resetConsumerToFirstRecordOffsets(consumer, pollResult);
				resetted = true;
			}
		}

		System.out.println("### Ending consuming task");
	}

	private List<MessageCount> constructMessageCounts(List<MessageData> values, Set<Long> allowedPartnerIds, Map<String, Boolean> prepaidPostpaidRegistry, Map<String, List<RoamingInterval>> roamingIntervals) {
		return values.stream()
				.filter(messageData -> isMessageDataOfInterest(allowedPartnerIds, messageData))
				.map(messageData -> {
					final LocalDate localDate = Instant.ofEpochMilli(messageData.getMessage().getReceived()).atZone(ZoneId.systemDefault()).toLocalDate();
					final Long partnerId = messageData.getContext().getPartnerId();
					final boolean mms = messageData.getMessage().getContentTypeIn().equals(MessageContentType.MMS);
					final String shortCode = messageData.getContext().getBssCode();
					final long count = messageData.getMessage().getDirection().equals(Direction.SEND) ? messageData.getContext().getOutgoingCnt() : messageData.getContext().getIncomingCnt();
					final MessageCountType messageCountType = resolveSubscriberCountType(messageData, prepaidPostpaidRegistry, roamingIntervals);
					return new MessageCount(localDate, partnerId, mms, shortCode, messageCountType, count);
				})
				.collect(Collectors.toList());
	}

	private void resetConsumerToFirstRecordOffsets(KafkaConsumer consumer, PollResult<MessageData> pollResult) {
		for (Map.Entry<TopicPartition, Long> entry : pollResult.getFirstRecordOffsetByPartition().entrySet()) {
			final TopicPartition partition = entry.getKey();
			long offset = entry.getValue();
			consumer.seek(partition, offset);
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
		consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 3);
		consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

		KafkaConsumer consumer = new KafkaConsumer<>(consumerConfig);
		consumer.subscribe(Collections.singletonList(inputTopic));

		return consumer;
	}

	private void produceInputValues() throws java.util.concurrent.ExecutionException, InterruptedException {
		Properties producerConfig = new Properties();
		producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
		producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
		producerConfig.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CLUSTER.schemaRegistryUrl());

		List<MessageData> inputValues = constructInputValues();

		IntegrationTestUtils.produceValuesSynchronously(inputTopic, inputValues, producerConfig);
	}

	private static MessageCountType resolveSubscriberCountType(MessageData messageData, Map<String, Boolean> prepaidPostpaidRegistry, Map<String, List<RoamingInterval>> roamingIntervals) {
		if (messageData.getMessage().getDirection().equals(Direction.SEND)) {
			final String msisdn = messageData.getMessage().getDest();
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
		else {
			final String msisdn = messageData.getMessage().getSrc();
			final Boolean prepaidType = prepaidPostpaidRegistry.get(msisdn);
			if (prepaidType == null) {
				return MessageCountType.MO_UNKNOWN;
			}
			else if (isSubscriberInRoaming(msisdn, Instant.ofEpochMilli(messageData.getMessage().getReceived()), roamingIntervals)) {
				return MessageCountType.MO_IN_ROAMING;
			}
			else if (prepaidType) {
				return MessageCountType.MO_PREPAID;
			}
			else {
				return MessageCountType.MO_POSTPAID;
			}
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

	private PollResult<MessageData> pollMgwMessages(KafkaConsumer consumer) {
		ConsumerRecords<?, MessageData> consumerRecords = consumer.poll(6000L);
		return new PollResult<>(consumerRecords);
	}

	private List<MessageData> constructInputValues() {
		List<MessageData> list = new ArrayList<>();
		list.add(constructMsgMessage("38591667", "385912392624", Direction.SEND, Instant.now(), false, 111L, 3, "someBssCode"));
		list.add(constructMsgMessage("38591667", "385912392624", Direction.SEND, Instant.now(), false, 112L, 3, "someBssCode"));
		list.add(constructMsgMessage("38591667", "385912392624", Direction.SEND, Instant.now(), false, 222L, 3, null));
		list.add(constructMsgMessage("38591667", "385912392624", Direction.SEND, Instant.now(), false, 222L, 3, "someOtherBssCode"));
		return list;
	}

	private MessageData constructMsgMessage(String src, String dest, Direction direction, Instant receivedInstant, boolean mms, Long partnerId, int individualMsgCount, String bssCode) {
		long time = receivedInstant.toEpochMilli();
		final MessageContentType contentType = mms ? MessageContentType.MMS : MessageContentType.TEXT;
		final String text = "Some content";
		final Integer incomingCnt = direction.equals(Direction.SEND) ? 1 : individualMsgCount;
		final Integer outgoingCnt = direction.equals(Direction.SEND) ? individualMsgCount : 1;
		final Message message = new Message(UUID.randomUUID().toString(), null, true, src, dest, direction, time, null, null, false, contentType, "Some subject", ByteBuffer.wrap(text.getBytes()), text, null, null, null, false, Collections.EMPTY_LIST);
		final ImplicitChargingResult implicitChargingResult = new ImplicitChargingResult(Collections.singletonList("reservId1"), 232L, 2L, direction, false);
		final RoutingInfo routingInfo = new RoutingInfo(direction, 111L, 222L, 333L, 444L, null);
		final MessagingContext context = new MessagingContext(MessageDeliveryStatus.RECEIVED, null, false, partnerId, 222L, contentType, routingInfo, null, null, 1, 0, 0, 00, false, incomingCnt, outgoingCnt, 1, 200L, null, null, null, bssCode, null, implicitChargingResult, null, null, false, 2L, 3L);
		return new MessageData(message, context);
	}

	private boolean isMessageDataOfInterest(Set<Long> allowedPartnerIds, MessageData value) {
		return allowedPartnerIds.contains(value.getContext().getPartnerId()) && value.getContext().getBssCode() != null;
	}

	private static Set<Long> fetchPartnerIds() {
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

	private static Map<String, List<RoamingInterval>> constructSubscriberRoamingIntervals() {
		final Map<String, List<RoamingInterval>> map = new HashMap<>();
		final Instant now = Instant.now();
		map.put("385912392625", Lists.newArrayList(new RoamingInterval(true, now.minusSeconds(100), now.minusSeconds(80)), new RoamingInterval(false, now.minusSeconds(80), now.minusSeconds(30)), new RoamingInterval(true, now.minusSeconds(30), Instant.MAX)));
		map.put("385912392626", Lists.newArrayList(new RoamingInterval(true, now.minusSeconds(50), Instant.MAX)));
		return map;
	}
}
