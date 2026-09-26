package com.kartaguez.pocoma.domain.event;

/** Immutable, typed business fact with a stable semantic identity. */
public interface BusinessEvent {
	EventType eventType();
}
