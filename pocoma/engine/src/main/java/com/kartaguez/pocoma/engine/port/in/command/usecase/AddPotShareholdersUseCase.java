package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.security.UserContext;

public interface AddPotShareholdersUseCase {

	PotShareholdersSnapshot addPotShareholders(UserContext userContext, AddPotShareholdersCommand command);
}
