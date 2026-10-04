package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;

import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHeaderPort;

public final class UpdatePotDetailsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdatePotDetailsCommand> {

	private final PotContextPort potContextPort;
	private final PotHeaderPort potHeaderPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final PotAuthorizationGuard authorizationPolicy;

	public UpdatePotDetailsCommandUseCaseAdapter(PotContextPort potContextPort, PotHeaderPort potHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort, PotAuthorizationGuard authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potHeaderPort = requireNonNull(potHeaderPort, "potHeaderPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdatePotDetailsCommand> commandClass() { return UpdatePotDetailsCommand.class; }

	@Override
	public CommandUseCaseResult execute(CommandExecutionAuthorization authorization, UpdatePotDetailsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.updatePotDetails(invocation.recording(potContextPort), potHeaderPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.updatePotDetails(userContext, PotCommandInputMapper.toInput(command)));
	}
}
