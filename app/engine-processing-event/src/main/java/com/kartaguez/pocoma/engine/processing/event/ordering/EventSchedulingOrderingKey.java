package com.kartaguez.pocoma.engine.processing.event.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public record EventSchedulingOrderingKey(long potVersion, Instant createdAt, UUID eventId,
		PipelineDefinition trigger) implements Comparable<EventSchedulingOrderingKey> {
	public EventSchedulingOrderingKey {
		if (potVersion < 1) throw new IllegalArgumentException("potVersion must be positive");
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(trigger, "trigger must not be null");
	}

	@Override
	public int compareTo(EventSchedulingOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int compared = Long.compare(potVersion, other.potVersion);
		if (compared != 0) return compared;
		compared = createdAt.compareTo(other.createdAt);
		if (compared != 0) return compared;
		compared = eventId.toString().compareTo(other.eventId.toString());
		if (compared != 0) return compared;
		compared = trigger.pipelineId().value().compareTo(other.trigger.pipelineId().value());
		return compared != 0 ? compared
				: Integer.compare(trigger.pipelineVersion(), other.trigger.pipelineVersion());
	}
}
