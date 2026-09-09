package com.kartaguez.pocoma.engine.port.out.processing.event;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;

public record EventSchedulingCandidate(UUID eventId, PotId potId, long version, Instant createdAt,
		PipelineDefinition trigger) {
	public EventSchedulingCandidate {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(potId, "potId must not be null");
		if (version < 1) throw new IllegalArgumentException("version must be positive");
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(trigger, "trigger must not be null");
	}

	public EventSchedulingOrderingKey orderingKey() {
		return new EventSchedulingOrderingKey(version, createdAt, eventId, trigger);
	}
}
