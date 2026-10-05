package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;

import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotShareholdersPort;

public final class UpdatePotShareholdersWeightsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdatePotShareholdersWeightsCommand> {

	private final PotContextPort potContextPort;
	private final PotShareholdersPort potShareholdersPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final PotAuthorizationGuard authorizationPolicy;

	public UpdatePotShareholdersWeightsCommandUseCaseAdapter(PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort, PotGlobalVersionPort potGlobalVersionPort,
			PotAuthorizationGuard authorizationPolicy) {
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
			CommandExecutionAuthorization authorization,
			UpdatePotShareholdersWeightsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.updateShareholderWeights(
						invocation.recording(potContextPort), potShareholdersPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.updatePotShareholdersWeights(userContext, PotCommandInputMapper.toInput(command)));
	}
}
