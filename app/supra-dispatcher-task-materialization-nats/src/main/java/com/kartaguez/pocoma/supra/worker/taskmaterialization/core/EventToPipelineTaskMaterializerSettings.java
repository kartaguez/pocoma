package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import java.time.Duration;
import java.util.Objects;

import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;

public record EventToPipelineTaskMaterializerSettings(
		boolean enabled,
		String workerId,
		int batchSize,
		Duration pollingInterval,
		Duration safetyDelay,
		ProjectionPartition partition,
		boolean wakeSignalsEnabled) {

	public EventToPipelineTaskMaterializerSettings(
			boolean enabled,
			String workerId,
			int batchSize,
			Duration pollingInterval) {
		this(
				enabled,
				workerId,
				batchSize,
				pollingInterval,
				Duration.ZERO,
				ProjectionPartition.single(),
				true);
	}

	public EventToPipelineTaskMaterializerSettings {
		requireText(workerId, "workerId");
		requirePositive(batchSize, "batchSize");
		requirePositive(pollingInterval, "pollingInterval");
		requireNotNegative(safetyDelay, "safetyDelay");
		Objects.requireNonNull(partition, "partition must not be null");
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

	private static void requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
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
