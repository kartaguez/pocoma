package com.kartaguez.pocoma.engine.projection.task.engine;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationInvariantViolationException;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.TemporaryProjectionPreparationException;
import com.kartaguez.pocoma.engine.projection.task.TerminalProjectionPreparationException;

public final class ProjectionEngineService implements ExecuteProjectionTaskUseCase {
	private final ProjectionProducerCatalog catalog;
	private final ProjectionValidator validator;

	public ProjectionEngineService(ProjectionProducerCatalog catalog, ProjectionValidator validator) {
		this.catalog = requireNonNull(catalog, "catalog must not be null");
		this.validator = requireNonNull(validator, "validator must not be null");
	}

	@Override
	public ProjectionPreparationOutcome execute(ProjectionTask task) {
		requireNonNull(task, "task must not be null");
		ProjectionKey key = task.projectionKey();
		var declaration = catalog.find(key.projectionType()).orElseThrow(() -> invariant(key,
				"no projection producer configured for " + key.projectionType().value(), null));
		if (!declaration.targetObjectType().equals(key.targetObjectType())) {
			throw invariant(key, "producer does not support requested target object type", null);
		}
		return executeDeclaration(key, declaration);
	}

	private <I> ProjectionPreparationOutcome executeDeclaration(
			ProjectionKey key, ProjectionProducerDeclaration<I> declaration) {
		try {
			I input = requireNonNull(declaration.loader().load(key), "projection input must not be null");
			var projection = requireNonNull(declaration.projector().project(key, input),
					"projection must not be null");
			if (!projection.projectionKey().equals(key)) {
				throw invariant(key, "projector returned another ProjectionKey", null);
			}
			return new ProjectionPreparationOutcome.Prepared(
					validator.validate(declaration.definition(), projection));
		} catch (TemporaryProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Temporary(exception.failure());
		} catch (TerminalProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Terminal(exception.failure());
		} catch (ProjectionPreparationInvariantViolationException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw invariant(key, "projection preparation invariant failed", exception);
		}
	}

	private static ProjectionPreparationInvariantViolationException invariant(
			ProjectionKey key, String message, Throwable cause) {
		return new ProjectionPreparationInvariantViolationException(key, message, cause);
	}
}
