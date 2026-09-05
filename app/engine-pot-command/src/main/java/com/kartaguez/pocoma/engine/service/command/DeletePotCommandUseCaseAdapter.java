package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;

public final class DeletePotCommandUseCaseAdapter extends AbstractPotCommandUseCaseAdapter<DeletePotCommand> {

	private final PotContextPort potContextPort;
	private final PotHeaderPort potHeaderPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final DeletePotAuthorizationPolicy authorizationPolicy;

	public DeletePotCommandUseCaseAdapter(PotContextPort potContextPort, PotHeaderPort potHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort, DeletePotAuthorizationPolicy authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potHeaderPort = requireNonNull(potHeaderPort, "potHeaderPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<DeletePotCommand> commandClass() { return DeletePotCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, DeletePotCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new DeletePotService(invocation.recording(potContextPort), potHeaderPort, potGlobalVersionPort,
						potHeaderPort, invocation, authorizationPolicy).deletePot(userContext, command));
	}
}
