package com.kartaguez.pocoma.engine.projection.task.engine;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
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
			I input = declaration.loader().load(key);
			if (input == null) {
				throw invariant(key, "loader returned null projection input", null);
			}
			var projection = declaration.projector().project(key, input);
			if (projection == null) {
				throw invariant(key, "projector returned null projection", null);
			}
			if (!projection.projectionKey().equals(key)) {
				throw invariant(key, "projector returned another ProjectionKey", null);
			}
			return new ProjectionPreparationOutcome.Prepared(
					validator.validate(declaration.definition(), projection));
		} catch (TemporaryProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Temporary(exception.failure());
		} catch (TerminalProjectionPreparationException exception) {
			return new ProjectionPreparationOutcome.Terminal(exception.failure());
		} catch (ProjectionValidationException exception) {
			throw invariant(key, "projection does not satisfy its canonical definition", exception);
		} catch (ProjectionPreparationInvariantViolationException exception) {
			throw exception;
		}
	}

	private static ProjectionPreparationInvariantViolationException invariant(
			ProjectionKey key, String message, Throwable cause) {
		return new ProjectionPreparationInvariantViolationException(key, message, cause);
	}
}
