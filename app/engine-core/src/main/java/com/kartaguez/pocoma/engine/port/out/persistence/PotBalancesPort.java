package com.kartaguez.pocoma.engine.port.out.persistence;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;

public interface PotBalancesPort {

	default Optional<PotBalanceProjectionState> loadProjectionState(PotId potId) {
		throw new UnsupportedOperationException("Pot balance projection state loading is not implemented");
	}

	default PotBalances loadAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("Pot balances loading is not implemented");
	}

	default void saveInitial(PotBalances potBalances) {
		throw new UnsupportedOperationException("Initial pot balances saving is not implemented");
	}

	default void save(PotBalances potBalances, long expectedProjectedVersion) {
		throw new UnsupportedOperationException("Pot balances saving is not implemented");
	}

	default void saveFull(PotBalances potBalances) {
		throw new UnsupportedOperationException("Full pot balances saving is not implemented");
	}
}
