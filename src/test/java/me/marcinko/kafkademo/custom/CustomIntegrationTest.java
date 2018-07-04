package me.marcinko.kafkademo.custom;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import me.marcinko.kafkademo.utils.EmbeddedSingleNodeKafkaCluster;
import me.marcinko.kafkademo.utils.IntegrationTestUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.test.TestUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test based on {@link WordCountLambdaExample}, using an embedded Kafka cluster.
 * <p>
 * See {@link WordCountLambdaExample} for further documentation.
 * <p>
 * See {@link WordCountScalaIntegrationTest} for the equivalent Scala example.
 * <p>
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 */
public class CustomIntegrationTest {

	@ClassRule
	public static final EmbeddedSingleNodeKafkaCluster CLUSTER = new EmbeddedSingleNodeKafkaCluster();

	private static final String INPUT_TOPIC_NAME = "inputTopic";
	private static final String OUTPUT_TOPIC_NAME = "outputTopic";

	@BeforeClass
	public static void startKafkaCluster() throws Exception {
		CLUSTER.createTopic(INPUT_TOPIC_NAME);
		CLUSTER.createTopic(OUTPUT_TOPIC_NAME);
	}

	@Test
	public void shouldCountWords() throws Exception {
		//
		// Step 1: Configure and start the processor topology.
		//

		final KafkaStreams streams = constructProcessingFlow();
		streams.start();

		//
		// Step 2: Produce some input data to the input topic.
		//
		Properties producerConfig = new Properties();
		producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
		producerConfig.put(ProducerConfig.RETRIES_CONFIG, 0);
		producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

		List<String> inputValues = Arrays.asList(
				"Hello Kafka Streams",
				"All streams lead to Kafka",
				"Join Kafka Summit",
				"И теперь пошли русские слова"
		);
		IntegrationTestUtils.produceValuesSynchronously(INPUT_TOPIC_NAME, inputValues, producerConfig);

		//
		// Step 3: Verify the application's output data.
		//
		Properties consumerConfig = new Properties();
		consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "wordcount-lambda-integration-test-standard-consumer");
		consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);

		final List<KeyValue<String, Long>> expectedWordCounts = Arrays.asList(
				new KeyValue<>("hello", 1L),
				new KeyValue<>("all", 1L),
				new KeyValue<>("lead", 1L),
				new KeyValue<>("to", 1L),
				new KeyValue<>("join", 1L),
				new KeyValue<>("kafka", 3L),
				new KeyValue<>("и", 1L),
				new KeyValue<>("теперь", 1L),
				new KeyValue<>("пошли", 1L),
				new KeyValue<>("русские", 1L),
				new KeyValue<>("слова", 1L)
		);

		List<KeyValue<String, Long>> actualWordCounts = IntegrationTestUtils.waitUntilMinKeyValueRecordsReceived(consumerConfig, OUTPUT_TOPIC_NAME, expectedWordCounts.size());
		streams.close();
		assertThat(actualWordCounts).containsExactlyElementsOf(expectedWordCounts);
	}

	private KafkaStreams constructProcessingFlow() {
		final Pattern pattern = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

		final StreamsBuilder builder = new StreamsBuilder();
		final KStream<String, String> textLines = builder.stream(INPUT_TOPIC_NAME);
		final KTable<String, Long> wordCounts = textLines
				.flatMapValues(value -> Arrays.asList(pattern.split(value.toLowerCase())))
				// no need to specify explicit serdes because the resulting key and value types match our default serde settings
				.filter((key, value) -> !value.toLowerCase().startsWith("s"))
				.groupBy((key, word) -> word)
				.count();

		wordCounts.toStream().to(OUTPUT_TOPIC_NAME, Produced.with(Serdes.String(), Serdes.Long()));

		final Topology topology = builder.build();
		final Properties streamsConfiguration = constructStreamsConfiguration();

		return new KafkaStreams(topology, streamsConfiguration);
	}

	private Properties constructStreamsConfiguration() {
		final Properties streamsConfiguration = new Properties();
		streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-lambda-integration-test");
		streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, CLUSTER.bootstrapServers());
		streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
		// The commit interval for flushing records to state stores and downstream must be lower than
		// this integration test's timeout (30 secs) to ensure we observe the expected processing results.
		streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);
		streamsConfiguration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		// Use a temporary directory for storing state, which will be automatically removed after the test.
		streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getAbsolutePath());
		return streamsConfiguration;
	}

}