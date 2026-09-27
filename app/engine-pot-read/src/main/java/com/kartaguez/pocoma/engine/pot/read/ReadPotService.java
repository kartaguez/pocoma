package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

final class ReadPotService implements ReadPotUseCase {
	private final ExactProjectionReadUseCase projectionRead;
	private final ReadPotInterpreter interpreter;

	ReadPotService(ExactProjectionReadUseCase projectionRead, ReadPotInterpreter interpreter) {
		this.projectionRead = requireNonNull(projectionRead, "projectionRead must not be null");
		this.interpreter = requireNonNull(interpreter, "interpreter must not be null");
	}

	@Override
	public ReadPotResult read(PotId potId, long targetVersion) {
		requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		ProjectionKey key = new ProjectionKey(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()),
				targetVersion);
		ProjectionReadResult result = projectionRead.get(key, ReadPotProjectionDefinition.DEFINITION);
		if (result instanceof ProjectionReadResult.Failed failed) {
			return new ReadPotResult.Failed(failed.projectionKey());
		}
		if (result instanceof ProjectionReadResult.NotReady notReady) {
			return new ReadPotResult.NotReady(notReady.projectionKey());
		}
		return new ReadPotResult.Ready(interpreter.interpret(((ProjectionReadResult.Ready) result).projection()));
	}
}
