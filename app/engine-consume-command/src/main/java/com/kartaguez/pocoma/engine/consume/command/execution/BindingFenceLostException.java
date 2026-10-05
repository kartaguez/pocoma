package com.kartaguez.pocoma.engine.consume.command.execution;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.CommandId;

/** Forces business transaction rollback before a fresh exact-authority check. */
public final class BindingFenceLostException extends RuntimeException {
	private final CommandId commandId;
	private final ExternalIdentity identity;
	private final BindingId bindingId;

	public BindingFenceLostException(CommandId commandId, ExternalIdentity identity, BindingId bindingId) {
		super("Binding fence lost for Command " + commandId.value());
		this.commandId = commandId;
		this.identity = identity;
		this.bindingId = bindingId;
	}

	public CommandId commandId() { return commandId; }
	public ExternalIdentity identity() { return identity; }
	public BindingId bindingId() { return bindingId; }
}
