package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.UpdatePotDetailsInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface UpdatePotDetailsUseCase {

	PotHeaderSnapshot updatePotDetails(UserContext userContext, UpdatePotDetailsInput command);
}
