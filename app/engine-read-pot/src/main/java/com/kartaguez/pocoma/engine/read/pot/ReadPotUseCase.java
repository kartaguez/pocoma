package com.kartaguez.pocoma.engine.read.pot;

import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface ReadPotUseCase {
	ReadPotResult read(UserId requestingUserId, TokenCapabilities capabilities, PotId potId, long targetVersion);
}
