package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.CreatePotInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface CreatePotUseCase {

	PotHeaderSnapshot createPot(UserContext userContext, CreatePotInput command);
}
