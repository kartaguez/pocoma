package com.kartaguez.pocoma.engine.read.commandresult;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.CommandId;

public interface GetCommandResultUseCase {
	GetCommandResult get(CommandId commandId, ExternalIdentity requester);
}
