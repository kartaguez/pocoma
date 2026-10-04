package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.DeletePotInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface DeletePotUseCase {

	PotHeaderSnapshot deletePot(UserContext userContext, DeletePotInput command);
}
