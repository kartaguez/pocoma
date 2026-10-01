package com.kartaguez.pocoma.engine.processing.event.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Transitional technical processing key ordering event consumptions.
 */
public record EventOrderingKey(long appliesAtVersion, Instant createdAt, UUID eventId)
		implements Comparable<EventOrderingKey> {

	public EventOrderingKey {
		if (appliesAtVersion <= 0) {
			throw new IllegalArgumentException("appliesAtVersion must be positive");
		}
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(eventId, "eventId must not be null");
	}

	@Override
	public int compareTo(EventOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int versionComparison = Long.compare(appliesAtVersion, other.appliesAtVersion);
		if (versionComparison != 0) {
			return versionComparison;
		}
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: eventId.toString().compareTo(other.eventId.toString());
	}
}
