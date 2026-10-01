package com.kartaguez.pocoma.engine.command.execution;

/** A deterministic violation of the technical Command execution contract. */
public final class CommandExecutionInvariantViolationException extends RuntimeException {

	public CommandExecutionInvariantViolationException(String message) {
		super(message);
	}

	public CommandExecutionInvariantViolationException(String message, Throwable cause) {
		super(message, cause);
	}
}
