package com.kartaguez.pocoma.engine.consume.command.model;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Explicit versioned Pot result produced by a successfully applied Command. */
public record CommandAppliedResult(UUID potId, long resultingVersion) {
	public CommandAppliedResult {
		requireNonNull(potId, "potId must not be null");
		if (resultingVersion < 1) {
			throw new IllegalArgumentException("resultingVersion must be greater than or equal to 1");
		}
	}
}
