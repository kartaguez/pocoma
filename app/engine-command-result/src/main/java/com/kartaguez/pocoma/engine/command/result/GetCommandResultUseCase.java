package com.kartaguez.pocoma.engine.command.result;

import java.util.UUID;

import com.kartaguez.pocoma.engine.command.model.CommandId;

public interface GetCommandResultUseCase {
	GetCommandResult get(CommandId commandId, UUID requestingUserId);
}
