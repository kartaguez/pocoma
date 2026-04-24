package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotShareholdersWeightsUseCase {

	PotShareholdersSnapshot updatePotShareholdersWeights(
			UserContext userContext,
			UpdatePotShareholdersWeightsCommand command);
}
