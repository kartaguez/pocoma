package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;

public final class UpdatePotShareholdersWeightsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdatePotShareholdersWeightsCommand> {

	private final PotContextPort potContextPort;
	private final PotShareholdersPort potShareholdersPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final UpdatePotShareholdersWeightsAuthorizationPolicy authorizationPolicy;

	public UpdatePotShareholdersWeightsCommandUseCaseAdapter(PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort, PotGlobalVersionPort potGlobalVersionPort,
			UpdatePotShareholdersWeightsAuthorizationPolicy authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potShareholdersPort = requireNonNull(potShareholdersPort, "potShareholdersPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdatePotShareholdersWeightsCommand> commandClass() {
		return UpdatePotShareholdersWeightsCommand.class;
	}

	@Override
	public CommandUseCaseResult execute(
			AuthorizationSnapshot authorization,
			UpdatePotShareholdersWeightsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new UpdatePotShareholdersWeightsService(invocation.recording(potContextPort), potShareholdersPort,
						potGlobalVersionPort, potShareholdersPort, invocation, authorizationPolicy)
						.updatePotShareholdersWeights(userContext, command));
	}
}
