package com.kartaguez.pocoma.engine.command.decode;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandType;

/** Technical failure raised when a registered decoder cannot produce its declared Command type. */
public final class InvalidCommandPayloadException extends RuntimeException {

	public InvalidCommandPayloadException(CommandType commandType, String detail) {
		super(message(commandType, detail));
	}

	public InvalidCommandPayloadException(CommandType commandType, Throwable cause) {
		super(message(commandType, "decoder failed"), requireNonNull(cause, "cause must not be null"));
	}

	private static String message(CommandType commandType, String detail) {
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(detail, "detail must not be null");
		return "Invalid payload for Command type " + commandType.value() + ": " + detail;
	}
}
