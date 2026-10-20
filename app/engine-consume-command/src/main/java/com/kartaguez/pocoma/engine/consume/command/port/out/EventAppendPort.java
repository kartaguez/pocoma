package com.kartaguez.pocoma.engine.consume.command.port.out;

import java.util.List;

import com.kartaguez.pocoma.domain.event.BusinessEvent;

/** Atomic persistence boundary for typed business events produced by one Command. */
@FunctionalInterface
public interface EventAppendPort {

	/**
	 * Adapts, serializes and appends all events atomically, returning one provenance artifact and
	 * accepted publication reference per event, in the same order.
	 */
	com.kartaguez.pocoma.engine.consume.command.model.BusinessEventsAccepted appendAll(List<BusinessEvent> events);
}
