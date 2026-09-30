package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;

import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationKernel;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTarget;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationFactResolver;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

final class ReadPotService implements ReadPotUseCase {
	private static final RequiredCurrentCapabilities REQUIRED_CAPABILITIES =
			RequiredCurrentCapabilities.of(POT_VIEW);

	private final ExactProjectionReadUseCase projectionRead;
	private final AuthProjectionInterpreter authInterpreter;
	private final ReadPotInterpreter interpreter;
	private final PotAuthorizationFactResolver factResolver = new PotAuthorizationFactResolver();
	private final AuthorizationKernel authorization = new AuthorizationKernel();

	ReadPotService(ExactProjectionReadUseCase projectionRead, AuthProjectionInterpreter authInterpreter,
			ReadPotInterpreter interpreter) {
		this.projectionRead = requireNonNull(projectionRead, "projectionRead must not be null");
		this.authInterpreter = requireNonNull(authInterpreter, "authInterpreter must not be null");
		this.interpreter = requireNonNull(interpreter, "interpreter must not be null");
	}

	@Override
	public ReadPotResult read(UserId requestingUserId, TokenCapabilities capabilities,
			PotId potId, long targetVersion) {
		requireNonNull(requestingUserId, "requestingUserId must not be null");
		requireNonNull(capabilities, "capabilities must not be null");
		requireNonNull(potId, "potId must not be null");
		if (targetVersion < 1) {
			throw new IllegalArgumentException("targetVersion must be greater than or equal to 1");
		}
		if (!capabilities.containsAll(REQUIRED_CAPABILITIES)) return new ReadPotResult.Forbidden();

		ProjectionKey authKey = new ProjectionKey(
				AuthProjectionDefinition.PROJECTION_TYPE,
				AuthProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()),
				targetVersion);
		ProjectionReadResult authResult = projectionRead.get(authKey, AuthProjectionDefinition.DEFINITION);
		if (authResult instanceof ProjectionReadResult.Failed failed) {
			return new ReadPotResult.AuthFailed(failed.projectionKey());
		}
		if (authResult instanceof ProjectionReadResult.NotReady notReady) {
			return new ReadPotResult.AuthNotReady(notReady.projectionKey());
		}
		var relations = authInterpreter.interpret(((ProjectionReadResult.Ready) authResult).projection());
		var target = AuthorizationTarget.existing(potId);
		var facts = factResolver.resolve(relations, requestingUserId, target);
		if (!authorization.decide(capabilities, REQUIRED_CAPABILITIES, target, facts, PotAction.VIEW_POT)
				.isAllowed()) {
			return new ReadPotResult.Forbidden();
		}

		ProjectionKey readPotKey = new ProjectionKey(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()),
				targetVersion);
		ProjectionReadResult result = projectionRead.get(readPotKey, ReadPotProjectionDefinition.DEFINITION);
		if (result instanceof ProjectionReadResult.Failed failed) {
			return new ReadPotResult.ReadPotFailed(failed.projectionKey());
		}
		if (result instanceof ProjectionReadResult.NotReady notReady) {
			return new ReadPotResult.ReadPotNotReady(notReady.projectionKey());
		}
		return new ReadPotResult.Ready(interpreter.interpret(((ProjectionReadResult.Ready) result).projection()));
	}
}
