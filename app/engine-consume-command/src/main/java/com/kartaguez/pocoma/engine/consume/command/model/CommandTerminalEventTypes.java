package com.kartaguez.pocoma.engine.consume.command.model;

import java.util.Set;

import com.kartaguez.pocoma.domain.event.EventType;

public final class CommandTerminalEventTypes {
	public static final EventType COMMAND_APPLIED = new EventType("COMMAND_APPLIED");
	public static final EventType COMMAND_REJECTED = new EventType("COMMAND_REJECTED");
	public static final EventType COMMAND_FAILED = new EventType("COMMAND_FAILED");
	private static final Set<EventType> ALL = Set.of(COMMAND_APPLIED, COMMAND_REJECTED, COMMAND_FAILED);

	private CommandTerminalEventTypes() {}

	public static Set<EventType> all() { return ALL; }
}
