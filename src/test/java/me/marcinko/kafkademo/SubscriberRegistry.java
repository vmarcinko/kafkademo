package me.marcinko.kafkademo;

import java.util.List;
import java.util.Map;
import java.util.Set;

import me.marcinko.kafkademo.mgw.message.RoamingInterval;

public interface SubscriberRegistry {
	Map<String, Boolean> resolvePrepaidRegistry(Set<String> msisdns);

	Map<String, List<RoamingInterval>> resolveRoamingRegistry(Set<String> msisdns);
}
