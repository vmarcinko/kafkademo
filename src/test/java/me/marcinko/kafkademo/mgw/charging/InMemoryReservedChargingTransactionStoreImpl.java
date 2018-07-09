package me.marcinko.kafkademo.mgw.charging;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InMemoryReservedChargingTransactionStoreImpl implements ReservedChargingTransactionStore {
	private final Logger logger = LoggerFactory.getLogger(InMemoryReservedChargingTransactionStoreImpl.class);
	private final Map<String, ChargingTransaction> transactionbyId = new ConcurrentHashMap<>();

	@Override
	public void store(Set<ChargingTransaction> transactions) {
		logger.info("Storing reserved transactions: {}", transactions);
		for (ChargingTransaction transaction : transactions) {
			transactionbyId.put(transaction.getReservationId(), transaction);
		}
	}

	@Override
	public List<ChargingTransaction> findByIdIn(Set<String> ids) {
		return transactionbyId.values().stream()
				.filter(chargingTransaction -> ids.contains(chargingTransaction.getReservationId()))
				.collect(Collectors.toList());
	}

	@Override
	public void deletByIdIn(Set<String> ids) {
		logger.info("Deleting transactions by IDs: {}", ids);
		for (String id : ids) {
			this.transactionbyId.remove(id);
		}
	}
}
