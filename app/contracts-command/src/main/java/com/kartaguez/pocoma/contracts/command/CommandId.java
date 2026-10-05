package com.kartaguez.pocoma.contracts.command;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** Durable identity of a Command. */
public record CommandId(UUID value) {

	public CommandId {
		requireNonNull(value, "value must not be null");
	}
}
