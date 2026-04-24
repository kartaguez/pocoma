package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface DeletePotUseCase {

	PotHeaderSnapshot deletePot(UserContext userContext, DeletePotCommand command);
}
