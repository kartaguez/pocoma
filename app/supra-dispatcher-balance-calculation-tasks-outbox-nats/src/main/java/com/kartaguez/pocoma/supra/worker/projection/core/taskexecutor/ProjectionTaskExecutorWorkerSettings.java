package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import java.time.Duration;
import java.util.Objects;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public record ProjectionTaskExecutorWorkerSettings(
		boolean enabled,
		String workerId,
		int batchSize,
		Duration pollingInterval,
		Duration leaseDuration,
		ProjectionPartition partition,
		boolean wakeSignalsEnabled) {

	public ProjectionTaskExecutorWorkerSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval,
			Duration leaseDuration) {
		this(enabled, workerId, batchSize, pollingInterval, leaseDuration, ProjectionPartition.single(), true);
	}

	public ProjectionTaskExecutorWorkerSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval,
			Duration leaseDuration,
			boolean wakeSignalsEnabled) {
		this(enabled, workerId, batchSize, pollingInterval, leaseDuration, ProjectionPartition.single(), wakeSignalsEnabled);
	}

	public ProjectionTaskExecutorWorkerSettings {
		Objects.requireNonNull(workerId, "workerId must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		if (workerId.isBlank()) {
			throw new IllegalArgumentException("workerId must not be blank");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be greater than or equal to 1");
		}
		requirePositive(pollingInterval, "pollingInterval");
		requirePositive(leaseDuration, "leaseDuration");
	}

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
