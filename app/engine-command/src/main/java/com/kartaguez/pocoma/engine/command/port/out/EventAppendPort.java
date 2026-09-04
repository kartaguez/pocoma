package com.kartaguez.pocoma.engine.command.port.out;

import java.util.List;

import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;

/** Atomic persistence boundary for typed business events produced by one Command. */
@FunctionalInterface
public interface EventAppendPort {

	/**
	 * Adapts, serializes and appends all events atomically, returning one artifact per
	 * event in the same order.
	 */
	List<CommandExecutionArtifact> appendAll(List<BusinessEvent> events);
}
