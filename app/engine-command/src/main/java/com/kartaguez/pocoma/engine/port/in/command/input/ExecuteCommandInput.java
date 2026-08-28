package com.kartaguez.pocoma.engine.port.in.command.input;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.command.intent.CommandIntent;
import com.kartaguez.pocoma.engine.security.UserContext;

/** Input used by generic incoming adapters to execute a typed command intent. */
public record ExecuteCommandInput(UserContext userContext, CommandIntent commandIntent) {

	public ExecuteCommandInput {
		requireNonNull(userContext, "userContext must not be null");
		requireNonNull(commandIntent, "commandIntent must not be null");
	}
}
