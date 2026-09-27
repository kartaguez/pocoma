package com.kartaguez.pocoma.engine.pot.read;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface ReadPotUseCase {
	ReadPotResult read(PotId potId, long targetVersion);
}
