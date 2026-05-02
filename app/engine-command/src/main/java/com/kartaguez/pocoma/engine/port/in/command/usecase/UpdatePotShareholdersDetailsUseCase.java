package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface UpdatePotShareholdersDetailsUseCase {

	PotShareholdersSnapshot updatePotShareholdersDetails(
			UserContext userContext,
			UpdatePotShareholdersDetailsCommand command);
}
