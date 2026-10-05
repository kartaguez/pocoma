package com.kartaguez.pocoma.supra.consume.lkv;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.RecordedEvent;

/** Authoritative structural reload of a durable Event. */
public interface EventPort {
	/** Reloads the authoritative event inside the caller's execution transaction. */
	Optional<RecordedEvent<? extends BusinessEvent>> findById(UUID eventId);
}
