package me.marcinko.kafkademo.mgw.charging;

import java.util.List;
import java.util.Set;

public interface ReservedChargingTransactionStore {
	void store(Set<ChargingTransaction> transactions);

	List<ChargingTransaction> findByIdIn(Set<String> ids);

	void deletByIdIn(Set<String> ids);
}
