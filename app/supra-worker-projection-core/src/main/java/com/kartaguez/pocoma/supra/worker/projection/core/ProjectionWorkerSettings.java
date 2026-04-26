package com.kartaguez.pocoma.supra.worker.projection.core;

import java.time.Duration;
import java.util.Objects;

public record ProjectionWorkerSettings(
		int threadCount,
		int queueCapacity,
		int maxRetries,
		Duration initialBackoff,
		Duration maxBackoff) {

	public static final int DEFAULT_THREAD_COUNT = 10;
	public static final int DEFAULT_QUEUE_CAPACITY = Integer.MAX_VALUE;
	public static final int DEFAULT_MAX_RETRIES = 3;
	public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);
	public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);

	public ProjectionWorkerSettings {
		requirePositive(threadCount, "threadCount");
		requirePositive(queueCapacity, "queueCapacity");
		requireNotNegative(maxRetries, "maxRetries");
		requireNotNegative(initialBackoff, "initialBackoff");
		requireNotNegative(maxBackoff, "maxBackoff");
		if (initialBackoff.compareTo(maxBackoff) > 0) {
			throw new IllegalArgumentException("initialBackoff must be less than or equal to maxBackoff");
		}
	}

	public static ProjectionWorkerSettings defaults() {
		return new ProjectionWorkerSettings(
				DEFAULT_THREAD_COUNT,
				DEFAULT_QUEUE_CAPACITY,
				DEFAULT_MAX_RETRIES,
				DEFAULT_INITIAL_BACKOFF,
				DEFAULT_MAX_BACKOFF);
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}

	private static void requireNotNegative(int value, String name) {
		if (value < 0) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 0");
		}
	}

	private static void requireNotNegative(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative()) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 0");
		}
	}
}
