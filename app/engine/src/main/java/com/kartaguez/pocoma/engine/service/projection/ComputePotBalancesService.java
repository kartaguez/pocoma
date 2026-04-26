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
		// 1. Validate the incoming projection request.
		Objects.requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}

		// 2. Load the current projection state, if any.
		PotBalanceProjectionState projectionState = potBalancesPort.loadProjectionState(potId)
				.orElse(null);

		// 3. Return the already projected balances when the requested version is covered.
		if (projectionState != null && targetVersion <= projectionState.projectedVersion()) {
			return potBalancesPort.loadAtVersion(potId, projectionState.projectedVersion());
		}

		// 4. Resolve the baseline balances from which the next projection will be computed.
		PotBalances previousBalances = projectionState == null
				? new PotBalances(potId, BASELINE_VERSION, java.util.Map.of())
				: potBalancesPort.loadAtVersion(potId, projectionState.projectedVersion());

		// 5. Persist and return the initial empty projection when the baseline is the target.
		if (targetVersion == previousBalances.version()) {
			potBalancesPort.saveInitial(previousBalances);
			return previousBalances;
		}

		// 6. Load the expense changes between the previous projected version and the target version.
		Collection<ProjectedExpense> activeAtPreviousOnly = projectedExpensePort.loadActiveAtSourceOnly(
				potId,
				previousBalances.version(),
				targetVersion);
		Collection<ProjectedExpense> activeAtTargetOnly = projectedExpensePort.loadActiveAtSourceOnly(
				potId,
				targetVersion,
				previousBalances.version());

		// 7. Compute the target balances by applying those expense changes.
		PotBalances targetBalances = potBalancesCalculator.calculate(
				previousBalances,
				targetVersion,
				activeAtPreviousOnly,
				activeAtTargetOnly);

		// 8. Persist the new projection state using optimistic progression from the previous state.
		if (projectionState == null) {
			potBalancesPort.saveInitial(targetBalances);
		}
		else {
			potBalancesPort.save(targetBalances, projectionState.projectedVersion());
		}

		// 9. Return the computed target balances to the caller.
		return targetBalances;
	}
}
