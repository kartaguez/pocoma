package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotDetailsUseCase {

	PotHeaderSnapshot updatePotDetails(UserContext userContext, UpdatePotDetailsCommand command);
}
