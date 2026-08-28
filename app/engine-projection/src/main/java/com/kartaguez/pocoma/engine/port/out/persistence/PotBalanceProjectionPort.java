package com.kartaguez.pocoma.engine.port.out.persistence;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.port.out.persistence.model.PotBalanceProjectionState;

/** Persistence required to calculate and advance the balance projection. */
public interface PotBalanceProjectionPort {

	Optional<PotBalanceProjectionState> loadProjectionState(PotId potId);

	PotBalances loadAtVersion(PotId potId, long version);

	void saveInitial(PotBalances potBalances);

	void save(PotBalances potBalances, long expectedProjectedVersion);

	void saveFull(PotBalances potBalances);
}
