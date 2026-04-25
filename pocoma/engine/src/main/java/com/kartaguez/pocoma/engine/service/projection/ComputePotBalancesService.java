package com.kartaguez.pocoma.engine.service.projection;

import java.util.Collection;
import java.util.Objects;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;

final class ComputePotBalancesService implements ComputePotBalancesUseCase {

	private static final long BASELINE_VERSION = 1;

	private final PotBalancesPort potBalancesPort;
	private final ProjectedExpensePort projectedExpensePort;
	private final PotBalancesCalculator potBalancesCalculator;

	ComputePotBalancesService(
			PotBalancesPort potBalancesPort,
			ProjectedExpensePort projectedExpensePort,
			PotBalancesCalculator potBalancesCalculator) {
		this.potBalancesPort = Objects.requireNonNull(potBalancesPort, "potBalancesPort must not be null");
		this.projectedExpensePort = Objects.requireNonNull(projectedExpensePort, "projectedExpensePort must not be null");
		this.potBalancesCalculator = Objects.requireNonNull(potBalancesCalculator, "potBalancesCalculator must not be null");
	}

	@Override
	public PotBalances computePotBalances(PotId potId, long targetVersion) {
		Objects.requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}

		PotBalanceProjectionState projectionState = potBalancesPort.loadProjectionState(potId)
				.orElse(null);
		if (projectionState != null && targetVersion <= projectionState.projectedVersion()) {
			return potBalancesPort.loadAtVersion(potId, projectionState.projectedVersion());
		}

		PotBalances previousBalances = projectionState == null
				? new PotBalances(potId, BASELINE_VERSION, java.util.Map.of())
				: potBalancesPort.loadAtVersion(potId, projectionState.projectedVersion());

		if (targetVersion == previousBalances.version()) {
			potBalancesPort.saveInitial(previousBalances);
			return previousBalances;
		}

		Collection<ProjectedExpense> activeAtPreviousOnly = projectedExpensePort.loadActiveAtSourceOnly(
				potId,
				previousBalances.version(),
				targetVersion);
		Collection<ProjectedExpense> activeAtTargetOnly = projectedExpensePort.loadActiveAtSourceOnly(
				potId,
				targetVersion,
				previousBalances.version());
		PotBalances targetBalances = potBalancesCalculator.calculate(
				previousBalances,
				targetVersion,
				activeAtPreviousOnly,
				activeAtTargetOnly);

		if (projectionState == null) {
			potBalancesPort.saveInitial(targetBalances);
		}
		else {
			potBalancesPort.save(targetBalances, projectionState.projectedVersion());
		}

		return targetBalances;
	}
}
