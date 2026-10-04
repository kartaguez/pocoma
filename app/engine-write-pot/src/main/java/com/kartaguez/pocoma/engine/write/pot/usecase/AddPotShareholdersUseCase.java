package com.kartaguez.pocoma.engine.write.pot.usecase;

import com.kartaguez.pocoma.engine.write.pot.input.AddPotShareholdersInput;
import com.kartaguez.pocoma.engine.write.pot.snapshot.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

public interface AddPotShareholdersUseCase {

	PotShareholdersSnapshot addPotShareholders(UserContext userContext, AddPotShareholdersInput command);
}
