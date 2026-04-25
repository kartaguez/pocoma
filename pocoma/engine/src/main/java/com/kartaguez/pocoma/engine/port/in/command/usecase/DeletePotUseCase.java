package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface DeletePotUseCase {

	PotHeaderSnapshot deletePot(UserContext userContext, DeletePotCommand command);
}
