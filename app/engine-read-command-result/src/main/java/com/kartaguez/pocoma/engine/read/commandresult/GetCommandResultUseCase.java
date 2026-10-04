package com.kartaguez.pocoma.engine.read.commandresult;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.model.CommandId;

public interface GetCommandResultUseCase {
	GetCommandResult get(CommandId commandId, ExternalIdentity requester);
}
