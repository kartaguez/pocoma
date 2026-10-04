package com.kartaguez.pocoma.engine.port.out.processing.event;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.processing.event.ordering.ProjectionMaterializationOrderingKey;

/** One metadata-only Event to Projection consequence that is not yet known to be DONE. */
public record ProjectionMaterializationCandidate(
		UUID eventId,
		EventType eventType,
		ProjectionType projectionType,
		TargetObjectType targetObjectType,
		TargetObjectId targetObjectId,
		long targetVersion,
		Instant recordedAt) {

	public ProjectionMaterializationCandidate {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(eventType, "eventType must not be null");
		requireNonNull(projectionType, "projectionType must not be null");
		requireNonNull(targetObjectType, "targetObjectType must not be null");
		requireNonNull(targetObjectId, "targetObjectId must not be null");
		if (targetVersion < 1) throw new IllegalArgumentException("targetVersion must be positive");
		requireNonNull(recordedAt, "recordedAt must not be null");
	}

	public ProjectionMaterializationOrderingKey orderingKey() {
		return new ProjectionMaterializationOrderingKey(recordedAt, eventId, projectionType);
	}
}
