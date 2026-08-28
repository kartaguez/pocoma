package com.kartaguez.pocoma.engine.processing.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Transitional technical processing key ordering event consumptions.
 */
public record EventConsumptionOrderingKey(long appliesAtVersion, Instant createdAt, UUID eventId)
		implements Comparable<EventConsumptionOrderingKey> {

	public EventConsumptionOrderingKey {
		if (appliesAtVersion <= 0) {
			throw new IllegalArgumentException("appliesAtVersion must be positive");
		}
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(eventId, "eventId must not be null");
	}

	@Override
	public int compareTo(EventConsumptionOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int versionComparison = Long.compare(appliesAtVersion, other.appliesAtVersion);
		if (versionComparison != 0) {
			return versionComparison;
		}
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: OrderingComparisons.compareUuid(eventId, other.eventId);
	}
}
