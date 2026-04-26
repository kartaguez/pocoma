package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface CreatePotUseCase {

	PotHeaderSnapshot createPot(UserContext userContext, CreatePotCommand command);
}
