package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.command.intent.CommandIntent;

public final class UnsupportedCommandIntentException extends RuntimeException {

	private final Class<? extends CommandIntent> intentType;

	public UnsupportedCommandIntentException(CommandIntent intent) {
		super("Unsupported CommandIntent type: " + requireNonNull(intent, "intent must not be null").getClass().getName());
		this.intentType = intent.getClass();
	}

	public Class<? extends CommandIntent> intentType() {
		return intentType;
	}
}
