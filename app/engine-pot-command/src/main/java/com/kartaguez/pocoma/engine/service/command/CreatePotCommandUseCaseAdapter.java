package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;

public final class CreatePotCommandUseCaseAdapter extends AbstractPotCommandUseCaseAdapter<CreatePotCommand> {

	private final PotGlobalVersionPort potGlobalVersionPort;
	private final PotHeaderPort potHeaderPort;
	private final CreatePotAuthorizationPolicy authorizationPolicy;

	public CreatePotCommandUseCaseAdapter(PotGlobalVersionPort potGlobalVersionPort, PotHeaderPort potHeaderPort,
			CreatePotAuthorizationPolicy authorizationPolicy) {
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.potHeaderPort = requireNonNull(potHeaderPort, "potHeaderPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<CreatePotCommand> commandClass() { return CreatePotCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, CreatePotCommand command) {
		requireNonNull(authorization, "authorization must not be null");
		requireNonNull(command, "command must not be null");
		if (!command.creatorId().equals(authorization.userId().value())) {
			return new CommandUseCaseResult.Rejected(new TerminalReason("NOT_AUTHORIZED_ON_RESOURCE"), List.of());
		}
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.createPot(
						potGlobalVersionPort, potHeaderPort, invocation, authorizationPolicy)
						.createPot(userContext, command));
	}
}
