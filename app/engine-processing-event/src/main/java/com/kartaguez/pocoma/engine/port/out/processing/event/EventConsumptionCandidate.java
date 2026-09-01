package com.kartaguez.pocoma.engine.port.out.processing.event;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;

/** Structural identity of a durable Event that appears eligible for consumption. */
public record EventConsumptionCandidate(UUID eventId, PotId potId, long version, Instant createdAt) {
	public EventConsumptionCandidate {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(potId, "potId must not be null");
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		requireNonNull(createdAt, "createdAt must not be null");
	}

	public EventOrderingKey orderingKey() {
		return new EventOrderingKey(version, createdAt, eventId);
	}
}
