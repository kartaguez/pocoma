package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotShareholdersDetailsUseCase {

	PotShareholdersSnapshot updatePotShareholdersDetails(
			UserContext userContext,
			UpdatePotShareholdersDetailsCommand command);
}
