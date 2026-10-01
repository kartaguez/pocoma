package com.kartaguez.pocoma.engine.command.result;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.model.CommandId;

public interface GetCommandResultUseCase {
	GetCommandResult get(CommandId commandId, ExternalIdentity requester);
}
