package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import java.time.Duration;
import java.util.Objects;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public record ProjectionTaskBuilderSettings(
		boolean enabled,
		String workerId,
		int batchSize,
		Duration pollingInterval,
		Duration leaseDuration,
		ProjectionPartition partition,
		boolean wakeSignalsEnabled,
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

	public ProjectionTaskBuilderSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval,
			Duration leaseDuration) {
		this(enabled, workerId, batchSize, pollingInterval, leaseDuration, ProjectionPartition.single(), true);
	}

	public ProjectionTaskBuilderSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval,
			Duration leaseDuration,
			boolean wakeSignalsEnabled) {
		this(enabled, workerId, batchSize, pollingInterval, leaseDuration, ProjectionPartition.single(), wakeSignalsEnabled);
	}

	public ProjectionTaskBuilderSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval,
			Duration leaseDuration,
			ProjectionPartition partition,
			boolean wakeSignalsEnabled) {
		this(
				enabled,
				workerId,
				batchSize,
				pollingInterval,
				leaseDuration,
				partition,
				wakeSignalsEnabled,
				DEFAULT_THREAD_COUNT,
				DEFAULT_QUEUE_CAPACITY,
				DEFAULT_MAX_RETRIES,
				DEFAULT_INITIAL_BACKOFF,
				DEFAULT_MAX_BACKOFF);
	}

	public ProjectionTaskBuilderSettings {
		Objects.requireNonNull(workerId, "workerId must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		if (workerId.isBlank()) {
			throw new IllegalArgumentException("workerId must not be blank");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be greater than or equal to 1");
		}
		if (threadCount < 1) {
			throw new IllegalArgumentException("threadCount must be greater than or equal to 1");
		}
		if (queueCapacity < 1) {
			throw new IllegalArgumentException("queueCapacity must be greater than or equal to 1");
		}
		if (maxRetries < 0) {
			throw new IllegalArgumentException("maxRetries must be greater than or equal to 0");
		}
		requirePositive(pollingInterval, "pollingInterval");
		requirePositive(leaseDuration, "leaseDuration");
		requireNotNegative(initialBackoff, "initialBackoff");
		requireNotNegative(maxBackoff, "maxBackoff");
		if (initialBackoff.compareTo(maxBackoff) > 0) {
			throw new IllegalArgumentException("initialBackoff must be less than or equal to maxBackoff");
		}
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static void requireNotNegative(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative()) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 0");
		}
	}
}
