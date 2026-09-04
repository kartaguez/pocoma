package com.kartaguez.pocoma.engine.command.port.out;

import java.util.List;

import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandProducedEvent;

/** Atomic append boundary for provider-neutral events produced by one Command. */
@FunctionalInterface
public interface EventAppendPort {

	/** Returns one artifact per event, in the same order. */
	List<CommandExecutionArtifact> appendAll(List<CommandProducedEvent> events);
}
