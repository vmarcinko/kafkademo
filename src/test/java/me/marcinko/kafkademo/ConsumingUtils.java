package me.marcinko.kafkademo;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

import hr.kapsch.mgw.messaging.message.avro.MessageData;

public class ConsumingUtils {
	private ConsumingUtils() {
	}

	public static void resetConsumerToFirstRecordOffsets(KafkaConsumer consumer, PollResult<?> pollResult) {
		for (Map.Entry<TopicPartition, Long> entry : pollResult.getFirstRecordOffsetByPartition().entrySet()) {
			final TopicPartition partition = entry.getKey();
			long offset = entry.getValue();
			consumer.seek(partition, offset);
		}
	}

	public static PollResult pollKafkaRecords(KafkaConsumer consumer) {
		ConsumerRecords<?, MessageData> consumerRecords = consumer.poll(6000L);
		return new PollResult<>(consumerRecords);
	}

}
