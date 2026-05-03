package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class CapacityNotifier<K> {

	private final WorkWakeBus<String, K> wakeBus;
	private final String signal;
	private final Duration minInterval;
	private final ConcurrentMap<K, Long> lastPublishedAtNanos = new ConcurrentHashMap<>();

	public CapacityNotifier(WorkWakeBus<String, K> wakeBus, String signal, Duration minInterval) {
		this.wakeBus = Objects.requireNonNull(wakeBus, "wakeBus must not be null");
		this.signal = requireText(signal, "signal");
		this.minInterval = Objects.requireNonNull(minInterval, "minInterval must not be null");
		if (minInterval.isNegative()) {
			throw new IllegalArgumentException("minInterval must not be negative");
		}
	}

	public void notifyCapacityAvailable(K key) {
		Objects.requireNonNull(key, "key must not be null");
		long now = System.nanoTime();
		long minIntervalNanos = minInterval.toNanos();
		Long publishedAt = lastPublishedAtNanos.compute(key, (ignored, previous) -> {
			if (previous == null || now - previous >= minIntervalNanos) {
				return now;
			}
			return previous;
		});
		if (publishedAt != null && publishedAt == now) {
			wakeBus.publish(signal, key);
		}
	}

	public static <K> CapacityNotifier<K> noop() {
		return new CapacityNotifier<>(WorkWakeBus.noop(), "NOOP", Duration.ZERO);
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
