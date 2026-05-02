package com.kartaguez.pocoma.supra.worker.projection.core.model;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;

public record ProjectionTask(
		UUID taskId,
		UUID claimToken,
		PotId potId,
		long targetVersion,
		String sourceEventType,
		String traceId,
		Long commandCommittedAtNanos,
		long eventSubmittedAtNanos) {

	public ProjectionTask(PotId potId, long targetVersion, String sourceEventType) {
		this(null, null, potId, targetVersion, sourceEventType, null, null, System.nanoTime());
	}

	public ProjectionTask(
			PotId potId,
			long targetVersion,
			String sourceEventType,
			String traceId,
			Long commandCommittedAtNanos,
			long eventSubmittedAtNanos) {
		this(null, null, potId, targetVersion, sourceEventType, traceId, commandCommittedAtNanos, eventSubmittedAtNanos);
	}

	public ProjectionTask(
			UUID taskId,
			UUID claimToken,
			PotId potId,
			long targetVersion,
			String sourceEventType,
			String traceId,
			Long commandCommittedAtNanos,
			long eventSubmittedAtNanos) {
		this.taskId = taskId;
		this.claimToken = claimToken;
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.targetVersion = targetVersion;
		this.sourceEventType = Objects.requireNonNull(sourceEventType, "sourceEventType must not be null");
		this.traceId = traceId;
		this.commandCommittedAtNanos = commandCommittedAtNanos;
		this.eventSubmittedAtNanos = eventSubmittedAtNanos;
		validate();
	}

	private void validate() {
		if (sourceEventType.isBlank()) {
			throw new IllegalArgumentException("sourceEventType must not be blank");
		}
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (eventSubmittedAtNanos < 0) {
			throw new IllegalArgumentException("eventSubmittedAtNanos must be positive or zero");
		}
	}

	public ProjectionObservationContext toObservationContext() {
		return new ProjectionObservationContext(
				potId.value().toString(),
				targetVersion,
				sourceEventType,
				traceId,
				commandCommittedAtNanos,
				eventSubmittedAtNanos);
	}
}
