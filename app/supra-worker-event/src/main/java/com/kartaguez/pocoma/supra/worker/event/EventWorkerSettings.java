package com.kartaguez.pocoma.supra.worker.event;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.orchestrator.claimable.pull.SingleItemPullLoopSettings;

/**
 * V1 settings without heartbeat. The normal duration is an operational bound, not a timeout.
 * Task-creation idempotence protects committed work; the claim token only fences lifecycle updates.
 */
public record EventWorkerSettings(
		boolean enabled,
		String workerId,
		Duration pollingInterval,
		Duration leaseDuration,
		Duration maxNormalProcessingDuration,
		WorkerSegment segment,
		PipelineDefinition pipeline,
		boolean wakeSignalsEnabled) {

	public EventWorkerSettings {
		requireText(workerId, "workerId");
		requirePositive(pollingInterval, "pollingInterval");
		requirePositive(leaseDuration, "leaseDuration");
		requirePositive(maxNormalProcessingDuration, "maxNormalProcessingDuration");
		requireNonNull(segment, "segment must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		Duration requiredLease;
		try {
			requiredLease = maxNormalProcessingDuration.multipliedBy(3);
		}
		catch (ArithmeticException exception) {
			throw new IllegalArgumentException("maxNormalProcessingDuration is too large", exception);
		}
		if (leaseDuration.compareTo(requiredLease) < 0) {
			throw new IllegalArgumentException(
					"leaseDuration must be at least three times maxNormalProcessingDuration");
		}
	}

	public SingleItemPullLoopSettings pullLoopSettings() {
		return new SingleItemPullLoopSettings(enabled, workerId, pollingInterval, wakeSignalsEnabled);
	}

	public WorkerId consumptionWorkerId() {
		return new WorkerId(workerId);
	}

	public ClaimLease claimLease() {
		return new ClaimLease(leaseDuration);
	}

	private static void requirePositive(Duration value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private static void requireText(String value, String name) {
		requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
