package me.marcinko.kafkademo.custom;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import me.marcinko.kafkademo.utils.EmbeddedSingleNodeKafkaCluster;
import me.marcinko.kafkademo.utils.IntegrationTestUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
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

import static org.junit.Assert.assertEquals;

public class MgwIntegrationTest {
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

		KafkaStreams streams = constructProcessingFlow();
		streams.start();

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

		final MessageData messageData = constructMsgMessage("38591667", "385912392624", Direction.SEND, Instant.now(), false, 123L, 3, "someBssCode");

		List<MessageData> inputValues = Collections.singletonList(messageData);
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
		List<MessageData> actualValues = IntegrationTestUtils.waitUntilMinValuesRecordsReceived(consumerConfig, outputTopic, inputValues.size());
		streams.close();
		assertEquals(inputValues, actualValues);
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

	private KafkaStreams constructProcessingFlow() {
		// Write the input data as-is to the output topic.
		//
		// Normally, because a) we have already configured the correct default serdes for keys and
		// values and b) the types for keys and values are the same for both the input topic and the
		// output topic, we would only need to define:
		//
		//   builder.stream(inputTopic).to(outputTopic);
		//
		// However, in the code below we intentionally override the default serdes in `to()` to
		// demonstrate how you can construct and configure a specific Avro serde manually.
		final Serde<String> stringSerde = Serdes.String();
		final Serde<MessageData> specificAvroSerde = new SpecificAvroSerde<>();
		// Note how we must manually call `configure()` on this serde to configure the schema registry
		// url.  This is different from the case of setting default serdes (see `streamsConfiguration`
		// above), which will be auto-configured based on the `StreamsConfiguration` instance.
		final boolean isKeySerde = false;
		specificAvroSerde.configure(
				Collections.singletonMap(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CLUSTER.schemaRegistryUrl()),
				isKeySerde);

		StreamsBuilder builder = new StreamsBuilder();
		KStream<String, MessageData> stream = builder.stream(inputTopic);
		stream.to(outputTopic, Produced.with(stringSerde, specificAvroSerde));

		Properties streamsConfiguration = constructStreamsConfiguration();
		return new KafkaStreams(builder.build(), streamsConfiguration);
	}

	private Properties constructStreamsConfiguration() {
		Properties streamsConfiguration = new Properties();
		streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "specific-avro-integration-test");
		streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.ByteArray().getClass().getName());
		streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, SpecificAvroSerde.class);
		streamsConfiguration.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, CLUSTER.schemaRegistryUrl());
		streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		return streamsConfiguration;
	}
}
