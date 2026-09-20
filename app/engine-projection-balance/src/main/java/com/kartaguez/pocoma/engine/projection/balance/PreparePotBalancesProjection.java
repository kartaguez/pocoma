package com.kartaguez.pocoma.engine.projection.balance;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationInvariantViolationException;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskPreparation;
import com.kartaguez.pocoma.engine.projection.task.TemporaryProjectionPreparationException;
import com.kartaguez.pocoma.engine.projection.task.TerminalProjectionPreparationException;

public final class PreparePotBalancesProjection implements ProjectionTaskPreparation {
	private final CalculatePotBalancesAtVersionUseCase calculator;
	private final PotBalancesProjector projector;
	private final ProjectionValidator validator;
	public PreparePotBalancesProjection(CalculatePotBalancesAtVersionUseCase calculator,
			PotBalancesProjector projector, ProjectionValidator validator) {
		this.calculator = requireNonNull(calculator); this.projector = requireNonNull(projector); this.validator = requireNonNull(validator);
	}
	public ProjectionPreparationOutcome prepare(ProjectionKey key) {
		try {
			var balances = calculator.calculate(PotId.of(java.util.UUID.fromString(key.targetObjectId().value())), key.targetVersion());
			var projection = projector.project(key, balances);
			if (!projection.projectionKey().equals(key)) throw new IllegalStateException("projector returned another key");
			ValidatedProjection validated = validator.validate(PotBalancesProjectionDefinition.DEFINITION, projection);
			return new ProjectionPreparationOutcome.Prepared(validated);
		} catch (TemporaryProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Temporary(exception.failure());
		} catch (TerminalProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Terminal(exception.failure());
		} catch (ProjectionPreparationInvariantViolationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ProjectionPreparationInvariantViolationException(key, "POT_BALANCES preparation invariant failed", exception);
		}
	}
}
