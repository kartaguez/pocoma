package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.time.Duration;
import java.util.Objects;

public record ProjectionTaskExecutorSettings(
		int threadCount,
		int queueCapacity,
		int maxRetries,
		Duration initialBackoff,
		Duration maxBackoff,
		Duration capacityWakeupMinInterval) {

	public static final int DEFAULT_THREAD_COUNT = 10;
	public static final int DEFAULT_QUEUE_CAPACITY = Integer.MAX_VALUE;
	public static final int DEFAULT_MAX_RETRIES = 3;
	public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(100);
	public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(2);
	public static final Duration DEFAULT_CAPACITY_WAKEUP_MIN_INTERVAL = Duration.ofMillis(10);

	public ProjectionTaskExecutorSettings(
			int threadCount,
			int queueCapacity,
			int maxRetries,
			Duration initialBackoff,
			Duration maxBackoff) {
		this(
				threadCount,
				queueCapacity,
				maxRetries,
				initialBackoff,
				maxBackoff,
				DEFAULT_CAPACITY_WAKEUP_MIN_INTERVAL);
	}

	public ProjectionTaskExecutorSettings {
		requirePositive(threadCount, "threadCount");
		requirePositive(queueCapacity, "queueCapacity");
		requireNotNegative(maxRetries, "maxRetries");
		requireNotNegative(initialBackoff, "initialBackoff");
		requireNotNegative(maxBackoff, "maxBackoff");
		requireNotNegative(capacityWakeupMinInterval, "capacityWakeupMinInterval");
		if (initialBackoff.compareTo(maxBackoff) > 0) {
			throw new IllegalArgumentException("initialBackoff must be less than or equal to maxBackoff");
		}
	}

	public static ProjectionTaskExecutorSettings defaults() {
		return new ProjectionTaskExecutorSettings(
				DEFAULT_THREAD_COUNT,
				DEFAULT_QUEUE_CAPACITY,
				DEFAULT_MAX_RETRIES,
				DEFAULT_INITIAL_BACKOFF,
				DEFAULT_MAX_BACKOFF,
				DEFAULT_CAPACITY_WAKEUP_MIN_INTERVAL);
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
