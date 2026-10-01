package com.kartaguez.pocoma.engine.service.projection.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.exception.projection.read.StoredProjectionInvariantViolationException;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;

final class ExactProjectionReadService implements ExactProjectionReadUseCase {
	private final ProjectionReadPort readPort;
	private final ProjectionValidator validator;

	ExactProjectionReadService(ProjectionReadPort readPort, ProjectionValidator validator) {
		this.readPort = requireNonNull(readPort, "readPort must not be null");
		this.validator = requireNonNull(validator, "validator must not be null");
	}

	@Override
	public ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition) {
		requireNonNull(key, "key must not be null");
		requireNonNull(definition, "definition must not be null");
		if (!key.projectionType().equals(definition.projectionType())) {
			throw new IllegalArgumentException("key projection type does not match definition");
		}
		if (!key.targetObjectType().equals(definition.targetObjectType())) {
			throw new IllegalArgumentException("key target object type does not match definition");
		}

		var storedProjection = readPort.findProjection(key);
		if (storedProjection.isPresent()) {
			var projection = storedProjection.orElseThrow();
			if (!projection.projectionKey().equals(key)) {
				throw new StoredProjectionInvariantViolationException(key, projection.projectionKey());
			}
			try {
				return new ProjectionReadResult.Ready(validator.validate(definition, projection));
			}
			catch (ProjectionValidationException exception) {
				throw new StoredProjectionInvariantViolationException(key, exception);
			}
		}

		return readPort.hasFailure(key)
				? new ProjectionReadResult.Failed(key)
				: new ProjectionReadResult.NotReady(key);
	}
}
