package com.kartaguez.pocoma.engine.command.dispatch;

import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Command;

/** Specialized functional use case for one decoded Command class. */
public interface CommandUseCase<C extends Command> {

	Class<C> commandClass();

	CommandUseCaseResult execute(AuthorizationSnapshot authorization, C command);
}
