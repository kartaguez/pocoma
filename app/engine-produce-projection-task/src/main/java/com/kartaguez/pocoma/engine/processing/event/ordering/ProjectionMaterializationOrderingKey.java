package com.kartaguez.pocoma.engine.processing.event.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ProjectionType;

/** Local, non-durable keyset cursor over expanded Event to Projection consequences. */
public record ProjectionMaterializationOrderingKey(
		Instant recordedAt,
		UUID eventId,
		ProjectionType projectionType) {

	public ProjectionMaterializationOrderingKey {
		requireNonNull(recordedAt, "recordedAt must not be null");
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(projectionType, "projectionType must not be null");
	}
}
