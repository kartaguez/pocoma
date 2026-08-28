package com.kartaguez.pocoma.engine.port.in.taskcreation.input;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

/** Optional observability metadata decorating a recorded business event. */
public record EventTraceMetadata(Optional<String> traceId, Optional<Long> commandCommittedAtNanos) {

	public EventTraceMetadata {
		traceId = requireNonNull(traceId, "traceId must not be null");
		commandCommittedAtNanos = requireNonNull(commandCommittedAtNanos,
				"commandCommittedAtNanos must not be null");
		traceId.ifPresent(value -> {
			if (value.isBlank()) {
				throw new IllegalArgumentException("traceId must not be blank when provided");
			}
		});
	}

	public static EventTraceMetadata empty() {
		return new EventTraceMetadata(Optional.empty(), Optional.empty());
	}

	public static EventTraceMetadata of(String traceId, Long commandCommittedAtNanos) {
		return new EventTraceMetadata(Optional.ofNullable(traceId), Optional.ofNullable(commandCommittedAtNanos));
	}
}
