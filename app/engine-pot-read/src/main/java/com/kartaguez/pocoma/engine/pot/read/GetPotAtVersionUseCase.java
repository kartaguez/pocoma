package com.kartaguez.pocoma.engine.pot.read;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public interface GetPotAtVersionUseCase {
	GetPotAtVersionResult get(UserId userId, Set<Permission> permissions, PotId potId, long version);
}
