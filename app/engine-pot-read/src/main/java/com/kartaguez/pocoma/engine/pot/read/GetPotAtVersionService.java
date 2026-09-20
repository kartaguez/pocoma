package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.authorization.PocomaPermissions;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

final class GetPotAtVersionService implements GetPotAtVersionUseCase {
	private final ExactProjectionReadUseCase projectionRead;
	private final AuthProjectionInterpreter authInterpreter;
	private final ReadPotInterpreter readPotInterpreter;

	GetPotAtVersionService(ExactProjectionReadUseCase projectionRead,
			AuthProjectionInterpreter authInterpreter, ReadPotInterpreter readPotInterpreter) {
		this.projectionRead = requireNonNull(projectionRead, "projectionRead must not be null");
		this.authInterpreter = requireNonNull(authInterpreter, "authInterpreter must not be null");
		this.readPotInterpreter = requireNonNull(readPotInterpreter, "readPotInterpreter must not be null");
	}

	@Override
	public GetPotAtVersionResult get(UserId userId, Set<Permission> permissions, PotId potId, long version) {
		requireNonNull(userId, "userId must not be null");
		requireNonNull(permissions, "permissions must not be null");
		permissions = Set.copyOf(permissions);
		requireNonNull(potId, "potId must not be null");
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}

		if (!permissions.contains(PocomaPermissions.POT_VIEW)) {
			return new GetPotAtVersionResult.Forbidden();
		}

		ProjectionKey authKey = new ProjectionKey(
				AuthProjectionDefinition.PROJECTION_TYPE,
				AuthProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()),
				version);
		ProjectionReadResult authResult = projectionRead.get(authKey, AuthProjectionDefinition.DEFINITION);
		if (authResult instanceof ProjectionReadResult.Failed) {
			return new GetPotAtVersionResult.AuthFailed();
		}
		if (authResult instanceof ProjectionReadResult.NotReady) {
			return new GetPotAtVersionResult.AuthNotReady();
		}

		var authProjection = authInterpreter.interpret(((ProjectionReadResult.Ready) authResult).projection());
		if (!authProjection.isCreator(userId) && authProjection.shareholderIdFor(userId).isEmpty()) {
			return new GetPotAtVersionResult.Forbidden();
		}

		ProjectionKey readPotKey = new ProjectionKey(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()),
				version);
		ProjectionReadResult readPotResult = projectionRead.get(readPotKey, ReadPotProjectionDefinition.DEFINITION);
		if (readPotResult instanceof ProjectionReadResult.Failed) {
			return new GetPotAtVersionResult.ReadPotFailed();
		}
		if (readPotResult instanceof ProjectionReadResult.NotReady) {
			return new GetPotAtVersionResult.ReadPotNotReady();
		}

		PotView pot = readPotInterpreter.interpret(((ProjectionReadResult.Ready) readPotResult).projection());
		return new GetPotAtVersionResult.Ready(pot);
	}
}
