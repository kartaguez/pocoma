package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

public final class GetCommandResultService implements GetCommandResultUseCase {
	private final ExactProjectionReadUseCase projections;
	private final LegacyCurrentBindingUserQuery currentBindingUsers;

	public GetCommandResultService(ExactProjectionReadUseCase projections,
			LegacyCurrentBindingUserQuery currentBindingUsers) {
		this.projections = requireNonNull(projections, "projections must not be null");
		this.currentBindingUsers = requireNonNull(currentBindingUsers, "currentBindingUsers must not be null");
	}

	@Override
	public GetCommandResult get(CommandId commandId, ExternalIdentity requester) {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(requester, "requester must not be null");
		ProjectionKey key = new ProjectionKey(CommandResultProjectionDefinition.PROJECTION_TYPE,
				CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(commandId.value().toString()), 1);
		ProjectionReadResult read = projections.get(key, CommandResultProjectionDefinition.DEFINITION);
		if (!(read instanceof ProjectionReadResult.Ready ready)) return new GetCommandResult.NotFound();
		return visibleResult(ready, requester);
	}

	private GetCommandResult visibleResult(ProjectionReadResult.Ready ready, ExternalIdentity requester) {
		ProjectionArtifact artifact = ready.projection().projection().artifacts().getFirst();
		if (!(artifact.payload() instanceof JsonObject object)) {
			throw new IllegalStateException("COMMAND_RESULT payload is not an object");
		}
		Map<String, JsonValue> values = object.values();
		boolean legacy = values.containsKey("submittedByUserId");
		boolean exact = values.containsKey("visibility") || values.containsKey("visibleToExternalIdentity");
		if (legacy == exact) throw new IllegalStateException("COMMAND_RESULT has an incoherent visibility shape");
		if (legacy) {
			UUID submittedBy = UUID.fromString(string(values, "submittedByUserId"));
			boolean visible = currentBindingUsers.findAttachedUser(requester)
					.map(userId -> userId.equals(submittedBy)).orElse(false);
			if (!visible) return new GetCommandResult.NotFound();
		} else {
			if (!"EXACT_EXTERNAL_IDENTITY".equals(string(values, "visibility"))) {
				throw new IllegalStateException("Unknown COMMAND_RESULT visibility");
			}
			JsonValue identityValue = values.get("visibleToExternalIdentity");
			if (!(identityValue instanceof JsonObject identity)) {
				throw new IllegalStateException("Invalid COMMAND_RESULT external identity");
			}
			ExternalIdentity visibleTo = new ExternalIdentity(string(identity.values(), "issuer"),
					string(identity.values(), "subject"));
			if (!visibleTo.equals(requester)) return new GetCommandResult.NotFound();
		}
		try {
			Instant resolvedAt = Instant.parse(string(values, "resolvedAt"));
			return switch (string(values, "outcome")) {
				case "APPLIED" -> new GetCommandResult.Applied(UUID.fromString(string(values, "potId")),
						((JsonNumber) values.get("resultingVersion")).value().longValueExact(), resolvedAt);
				case "REJECTED" -> new GetCommandResult.Rejected(string(values, "code"), resolvedAt);
				case "FAILED" -> new GetCommandResult.Failed(string(values, "code"), resolvedAt);
				default -> new GetCommandResult.NotFound();
			};
		} catch (IllegalArgumentException invalidOutcome) {
			throw new IllegalStateException("Invalid COMMAND_RESULT outcome", invalidOutcome);
		}
	}

	private static String string(Map<String, JsonValue> values, String field) {
		JsonValue value = values.get(field);
		if (value == null || value == JsonNull.INSTANCE || !(value instanceof JsonString text)) {
			throw new IllegalStateException("Invalid COMMAND_RESULT field " + field);
		}
		return text.value();
	}
}
