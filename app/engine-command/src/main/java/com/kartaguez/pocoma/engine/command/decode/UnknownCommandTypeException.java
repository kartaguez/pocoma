package com.kartaguez.pocoma.engine.command.decode;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandType;

/** Technical failure raised when no decoder owns a durable Command type. */
public final class UnknownCommandTypeException extends RuntimeException {

	public UnknownCommandTypeException(CommandType commandType) {
		super("No Command decoder registered for " + requireNonNull(commandType, "commandType must not be null").value());
	}
}
