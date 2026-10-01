package com.kartaguez.pocoma.engine.port.out.processing.event;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;

/** Authoritative structural reload of a durable Event. */
public interface EventPort {
	/** Reloads the authoritative event inside the caller's execution transaction. */
	Optional<RecordedEvent<? extends BusinessEvent>> findById(UUID eventId);
}
