package com.kartaguez.pocoma.engine.command.dispatch;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.Command;

/** Technical failure raised when no use case owns a decoded Command class. */
public final class MissingCommandUseCaseException extends RuntimeException {

	public MissingCommandUseCaseException(Class<? extends Command> commandClass) {
		super("No Command use case registered for "
				+ requireNonNull(commandClass, "commandClass must not be null").getName());
	}
}
