package com.kartaguez.pocoma.engine.consume.command.dispatch;

import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.model.Command;

/** Specialized functional use case for one decoded Command class. */
public interface CommandUseCase<C extends Command> {

	Class<C> commandClass();

	CommandUseCaseResult execute(CommandExecutionAuthorization authorization, C command);
}
