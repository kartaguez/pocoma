package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotDetailsUseCase {

	PotHeaderSnapshot updatePotDetails(UserContext userContext, UpdatePotDetailsCommand command);
}
