package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotShareholdersWeightsUseCase {

	PotShareholdersSnapshot updatePotShareholdersWeights(
			UserContext userContext,
			UpdatePotShareholdersWeightsCommand command);
}
