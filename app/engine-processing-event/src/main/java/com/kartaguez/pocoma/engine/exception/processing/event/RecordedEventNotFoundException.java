package com.kartaguez.pocoma.engine.exception.processing.event;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** The durable event selected earlier is no longer available to an authoritative execution. */
public final class RecordedEventNotFoundException extends RuntimeException {
	private static final long serialVersionUID = 1L;
	private final UUID eventId;

	public RecordedEventNotFoundException(UUID eventId) {
		super("Recorded Event not found: " + requireNonNull(eventId, "eventId must not be null"));
		this.eventId = eventId;
	}

	public UUID eventId() {
		return eventId;
	}
}
