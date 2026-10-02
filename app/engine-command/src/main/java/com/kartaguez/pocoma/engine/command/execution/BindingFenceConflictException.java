package com.kartaguez.pocoma.engine.command.execution;

/** The exact occurrence is still current after rollback; retry as a technical conflict. */
public final class BindingFenceConflictException extends RuntimeException {
	public BindingFenceConflictException(BindingFenceLostException cause) {
		super("Binding fence conflict for Command " + cause.commandId().value(), cause);
	}
}
