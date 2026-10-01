package com.kartaguez.pocoma.engine.projection.balance;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionInputLoader;

public final class PotBalancesProjectionInputLoader implements ProjectionInputLoader<PotBalances> {
	private final CalculatePotBalancesAtVersionUseCase calculator;

	public PotBalancesProjectionInputLoader(CalculatePotBalancesAtVersionUseCase calculator) {
		this.calculator = requireNonNull(calculator, "calculator must not be null");
	}

	@Override
	public PotBalances load(ProjectionKey key) {
		requireNonNull(key, "key must not be null");
		return calculator.calculate(PotId.of(UUID.fromString(key.targetObjectId().value())), key.targetVersion());
	}
}
