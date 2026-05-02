package com.kartaguez.pocoma.orchestrator.claimable.pool;

import java.time.Duration;
import java.util.Objects;

public record SegmentedWorkerPoolSettings(
		String workerName,
		int threadCount,
		int queueCapacity,
		int maxRetries,
		Duration initialBackoff,
		Duration maxBackoff) {

	public SegmentedWorkerPoolSettings {
		requireText(workerName, "workerName");
		requirePositive(threadCount, "threadCount");
		requirePositive(queueCapacity, "queueCapacity");
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must be greater than or equal to 0");
		}
		requireNonNegative(initialBackoff, "initialBackoff");
		requireNonNegative(maxBackoff, "maxBackoff");
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
}
