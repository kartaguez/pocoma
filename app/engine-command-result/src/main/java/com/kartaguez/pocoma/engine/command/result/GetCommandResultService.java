package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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

	public GetCommandResultService(ExactProjectionReadUseCase projections) {
		this.projections = requireNonNull(projections, "projections must not be null");
	}

	@Override
	public GetCommandResult get(CommandId commandId, UUID requestingUserId) {
		requireNonNull(commandId, "commandId must not be null");
		requireNonNull(requestingUserId, "requestingUserId must not be null");
		ProjectionKey key = new ProjectionKey(CommandResultProjectionDefinition.PROJECTION_TYPE,
				CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(commandId.value().toString()), 1);
		ProjectionReadResult read = projections.get(key, CommandResultProjectionDefinition.DEFINITION);
		if (read instanceof ProjectionReadResult.NotReady) return new GetCommandResult.NotReady();
		if (read instanceof ProjectionReadResult.Failed) return new GetCommandResult.ProjectionFailed();
		ProjectionArtifact artifact = ((ProjectionReadResult.Ready) read).projection().projection().artifacts().getFirst();
		if (!(artifact.payload() instanceof JsonObject object)) throw new IllegalStateException("Invalid COMMAND_RESULT payload");
		Map<String, JsonValue> values = object.values();
		UUID owner = UUID.fromString(string(values, "submittedByUserId"));
		if (!owner.equals(requestingUserId)) return new GetCommandResult.NotFound();
		Instant resolvedAt = Instant.parse(string(values, "resolvedAt"));
		return switch (string(values, "outcome")) {
			case "APPLIED" -> new GetCommandResult.Applied(UUID.fromString(string(values, "potId")),
					((JsonNumber) values.get("resultingVersion")).value().longValueExact(), resolvedAt);
			case "REJECTED" -> new GetCommandResult.Rejected(string(values, "code"), resolvedAt);
			case "FAILED" -> new GetCommandResult.Failed(string(values, "code"), resolvedAt);
			default -> throw new IllegalStateException("Unknown COMMAND_RESULT outcome");
		};
	}

	private static String string(Map<String, JsonValue> values, String field) {
		JsonValue value = values.get(field);
		if (value == null || value == JsonNull.INSTANCE || !(value instanceof JsonString text)) {
			throw new IllegalStateException("Invalid COMMAND_RESULT field " + field);
		}
		return text.value();
	}
}
