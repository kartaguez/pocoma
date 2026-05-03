package com.kartaguez.pocoma.orchestrator.claimable.pool;

import java.time.Duration;
import java.util.Objects;

public record SegmentedWorkerPoolSettings(
		String workerName,
		int threadCount,
		int queueCapacity,
		int maxRetries,
		Duration initialBackoff,
		Duration maxBackoff,
		Duration leaseDuration,
		Duration heartbeatInterval) {

	public SegmentedWorkerPoolSettings(
			String workerName,
			int threadCount,
			int queueCapacity,
			int maxRetries,
			Duration initialBackoff,
			Duration maxBackoff) {
		this(workerName, threadCount, queueCapacity, maxRetries, initialBackoff, maxBackoff, Duration.ofSeconds(30));
	}

	public SegmentedWorkerPoolSettings(
			String workerName,
			int threadCount,
			int queueCapacity,
			int maxRetries,
			Duration initialBackoff,
			Duration maxBackoff,
			Duration leaseDuration) {
		this(
				workerName,
				threadCount,
				queueCapacity,
				maxRetries,
				initialBackoff,
				maxBackoff,
				leaseDuration,
				defaultHeartbeatInterval(leaseDuration));
	}

	public SegmentedWorkerPoolSettings {
		requireText(workerName, "workerName");
		requirePositive(threadCount, "threadCount");
		requirePositive(queueCapacity, "queueCapacity");
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must be greater than or equal to 0");
		}
		requireNonNegative(initialBackoff, "initialBackoff");
		requireNonNegative(maxBackoff, "maxBackoff");
		requirePositive(leaseDuration, "leaseDuration");
		requirePositive(heartbeatInterval, "heartbeatInterval");
		if (maxBackoff.compareTo(initialBackoff) < 0) {
			throw new IllegalArgumentException("maxBackoff must be greater than or equal to initialBackoff");
		}
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}

	private static void requireNonNegative(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative()) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static Duration defaultHeartbeatInterval(Duration leaseDuration) {
		requirePositive(leaseDuration, "leaseDuration");
		Duration interval = leaseDuration.dividedBy(3);
		return interval.isZero() ? Duration.ofMillis(1) : interval;
	}
}
