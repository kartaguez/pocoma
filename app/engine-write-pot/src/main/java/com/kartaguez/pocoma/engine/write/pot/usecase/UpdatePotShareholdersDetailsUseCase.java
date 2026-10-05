package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.UpdatePotShareholdersDetailsInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface UpdatePotShareholdersDetailsUseCase {

	PotShareholdersSnapshot updatePotShareholdersDetails(
			UserContext userContext,
			UpdatePotShareholdersDetailsInput command);
}
