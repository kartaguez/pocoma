package com.kartaguez.pocoma.engine.projection.pot;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.pot.read.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationInvariantViolationException;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskPreparation;
import com.kartaguez.pocoma.engine.projection.task.TemporaryProjectionPreparationException;
import com.kartaguez.pocoma.engine.projection.task.TerminalProjectionPreparationException;

public final class PrepareReadPotProjection implements ProjectionTaskPreparation {
	private final ReadPotProjectionInputLoader loader;
	private final ReadPotProjector projector;
	private final ProjectionValidator validator;

	public PrepareReadPotProjection(ReadPotProjectionInputLoader loader, ReadPotProjector projector,
			ProjectionValidator validator) {
		this.loader = requireNonNull(loader); this.projector = requireNonNull(projector); this.validator = requireNonNull(validator);
	}

	public ProjectionPreparationOutcome prepare(ProjectionKey key) {
		try {
			var projection = projector.project(key, loader.load(key));
			if (!projection.projectionKey().equals(key)) throw new IllegalStateException("projector returned another key");
			ValidatedProjection validated = validator.validate(ReadPotProjectionDefinition.DEFINITION, projection);
			return new ProjectionPreparationOutcome.Prepared(validated);
		} catch (TemporaryProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Temporary(exception.failure());
		} catch (TerminalProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Terminal(exception.failure());
		} catch (ProjectionPreparationInvariantViolationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ProjectionPreparationInvariantViolationException(key, "READ_POT preparation invariant failed", exception);
		}
	}
}
