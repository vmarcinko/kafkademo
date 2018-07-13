package me.marcinko.kafkademo.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

public class PollResult<T> {
	private final Map<TopicPartition, Long> firstRecordOffsetByPartition;
	private final List<T> values;

	public PollResult(ConsumerRecords<?, T> consumerRecords) {
		Objects.requireNonNull(consumerRecords);

		this.firstRecordOffsetByPartition = resolveFirstRecordOffsetsByPartition(consumerRecords);
		this.values = mapToValues(consumerRecords);
	}

	private Map<TopicPartition, Long> resolveFirstRecordOffsetsByPartition(ConsumerRecords<?, T> consumerRecords) {
		Map<TopicPartition, Long> map = new HashMap<>();

		for (TopicPartition partition : consumerRecords.partitions()) {
			List<? extends ConsumerRecord<?, T>> topicRecords = consumerRecords.records(partition);
			if (!topicRecords.isEmpty()) {
				long offset = topicRecords.get(0).offset();
				map.put(partition, offset);
			}
		}
		return map;
	}

	private List<T> mapToValues(ConsumerRecords<?, T> consumerRecords) {
		final List<T> list = new ArrayList<>();
		for (ConsumerRecord<?, T> consumerRecord : consumerRecords) {
			list.add(consumerRecord.value());
		}
		return list;
	}

	public Map<TopicPartition, Long> getFirstRecordOffsetByPartition() {
		return firstRecordOffsetByPartition;
	}

	public List<T> getValues() {
		return values;
	}

	public void add(PollResult<T> addition) {
		Objects.requireNonNull(addition);
		checkAdditionOffsets(addition);

		for (Map.Entry<TopicPartition, Long> entry : addition.firstRecordOffsetByPartition.entrySet()) {
			final TopicPartition partition = entry.getKey();
			final Long offset = entry.getValue();
			if (!this.firstRecordOffsetByPartition.containsKey(partition)) {
				this.firstRecordOffsetByPartition.put(partition, offset);
			}
		}

		this.values.addAll(addition.values);
	}

	private void checkAdditionOffsets(PollResult<T> addition) {
		for (Map.Entry<TopicPartition, Long> entry : firstRecordOffsetByPartition.entrySet()) {
			final TopicPartition partition = entry.getKey();
			final Long offset = entry.getValue();

			Long additionOffset = addition.getFirstRecordOffsetByPartition().get(partition);
			if (additionOffset != null && !(offset < additionOffset)) {
				throw new IllegalArgumentException("Offset in addition result partition " + partition + " is not larger than current offset " + offset);
			}
		}
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("PollResult{");
		sb.append("firstRecordOffsetByPartition=").append(firstRecordOffsetByPartition);
		sb.append(", values=").append(values);
		sb.append('}');
		return sb.toString();
	}
}
