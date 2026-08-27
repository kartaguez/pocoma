package com.kartaguez.pocoma.domain.consumption.ordering;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Claim ordering key for a durable Command.
 */
public record CommandOrderingKey(Instant createdAt, UUID commandId)
		implements Comparable<CommandOrderingKey> {

	public CommandOrderingKey {
		requireNonNull(createdAt, "createdAt must not be null");
		requireNonNull(commandId, "commandId must not be null");
	}

	@Override
	public int compareTo(CommandOrderingKey other) {
		requireNonNull(other, "other must not be null");
		int creationComparison = createdAt.compareTo(other.createdAt);
		return creationComparison != 0
				? creationComparison
				: OrderingComparisons.compareUuid(commandId, other.commandId);
	}
}
