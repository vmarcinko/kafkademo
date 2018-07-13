package me.marcinko.kafkademo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.marcinko.kafkademo.mgw.message.RoamingInterval;

public class InMemorySubscriberRegistryImpl implements SubscriberRegistry {
	private final Map<String, Boolean> prepaidRegistry;
	private final Map<String, List<RoamingInterval>> roamingRegistry;

	public InMemorySubscriberRegistryImpl(Map<String, Boolean> prepaidRegistry, Map<String, List<RoamingInterval>> roamingRegistry) {
		this.prepaidRegistry = prepaidRegistry;
		this.roamingRegistry = roamingRegistry;
	}

	@Override
	public Map<String, Boolean> resolvePrepaidRegistry(Set<String> msisdns) {
		Map<String, Boolean> map = new HashMap<>(this.prepaidRegistry);
		map.keySet().retainAll(msisdns);
		return map;
	}

	@Override
	public Map<String, List<RoamingInterval>> resolveRoamingRegistry(Set<String> msisdns) {
		Map<String, List<RoamingInterval>> map = new HashMap<>(this.roamingRegistry);
		map.keySet().retainAll(msisdns);
		return map;
	}
}
