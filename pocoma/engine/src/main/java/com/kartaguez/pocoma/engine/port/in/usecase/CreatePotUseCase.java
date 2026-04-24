package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface CreatePotUseCase {

	PotHeaderSnapshot createPot(UserContext userContext, CreatePotCommand command);
}
