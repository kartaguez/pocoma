package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;

public final class AddPotShareholdersCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<AddPotShareholdersCommand> {

	private final PotContextPort potContextPort;
	private final PotShareholdersPort potShareholdersPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final AddPotShareholdersAuthorizationPolicy authorizationPolicy;

	public AddPotShareholdersCommandUseCaseAdapter(PotContextPort potContextPort,
			PotShareholdersPort potShareholdersPort, PotGlobalVersionPort potGlobalVersionPort,
			AddPotShareholdersAuthorizationPolicy authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potShareholdersPort = requireNonNull(potShareholdersPort, "potShareholdersPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<AddPotShareholdersCommand> commandClass() { return AddPotShareholdersCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, AddPotShareholdersCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new AddPotShareholdersService(invocation.recording(potContextPort), potShareholdersPort,
						potGlobalVersionPort, potShareholdersPort, invocation, authorizationPolicy)
						.addPotShareholders(userContext, command));
	}
}
