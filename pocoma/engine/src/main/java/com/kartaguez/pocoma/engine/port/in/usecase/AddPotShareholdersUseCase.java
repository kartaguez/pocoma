package com.kartaguez.pocoma.engine.port.in.usecase;

import com.kartaguez.pocoma.engine.port.in.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface AddPotShareholdersUseCase {

	PotShareholdersSnapshot addPotShareholders(UserContext userContext, AddPotShareholdersCommand command);
}
