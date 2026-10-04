package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.UpdatePotShareholdersWeightsInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface UpdatePotShareholdersWeightsUseCase {

	PotShareholdersSnapshot updatePotShareholdersWeights(
			UserContext userContext,
			UpdatePotShareholdersWeightsInput command);
}
