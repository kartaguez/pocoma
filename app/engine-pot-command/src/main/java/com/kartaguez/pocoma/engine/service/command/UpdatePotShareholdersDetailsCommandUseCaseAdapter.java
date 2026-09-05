package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;

public final class UpdatePotShareholdersDetailsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdatePotShareholdersDetailsCommand> {

	private final PotContextPort potContextPort;
	private final PotShareholdersPort potShareholdersPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final UpdatePotShareholdersDetailsAuthorizationPolicy authorizationPolicy;

	public UpdatePotShareholdersDetailsCommandUseCaseAdapter(PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort, PotGlobalVersionPort potGlobalVersionPort,
			UpdatePotShareholdersDetailsAuthorizationPolicy authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potShareholdersPort = requireNonNull(potShareholdersPort, "potShareholdersPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdatePotShareholdersDetailsCommand> commandClass() {
		return UpdatePotShareholdersDetailsCommand.class;
	}

	@Override
	public CommandUseCaseResult execute(
			AuthorizationSnapshot authorization,
			UpdatePotShareholdersDetailsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new UpdatePotShareholdersDetailsService(invocation.recording(potContextPort), potShareholdersPort,
						potGlobalVersionPort, potShareholdersPort, invocation, authorizationPolicy)
						.updatePotShareholdersDetails(userContext, command));
	}
}
