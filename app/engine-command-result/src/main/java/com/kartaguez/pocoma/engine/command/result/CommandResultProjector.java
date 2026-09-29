package com.kartaguez.pocoma.engine.command.result;

import java.util.List;
import java.util.Map;

import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionProjector;

public final class CommandResultProjector implements ProjectionProjector<CommandResultProjectionInput> {
	@Override
	public Projection project(ProjectionKey key, CommandResultProjectionInput input) {
		String commandId = input.outcome().commandId().value().toString();
		if (!key.projectionType().equals(CommandResultProjectionDefinition.PROJECTION_TYPE)
				|| !key.targetObjectType().equals(CommandResultProjectionDefinition.TARGET_OBJECT_TYPE)
				|| !key.targetObjectId().value().equals(commandId) || key.targetVersion() != 1) {
			throw new IllegalStateException("COMMAND_RESULT input does not match requested key");
		}
		JsonValue potId = JsonNull.INSTANCE;
		JsonValue version = JsonNull.INSTANCE;
		JsonValue code = JsonNull.INSTANCE;
		String outcome;
		if (input.outcome() instanceof CommandOutcome.Applied applied) {
			outcome = "APPLIED";
			potId = new JsonString(applied.potId().toString());
			version = new JsonNumber(java.math.BigDecimal.valueOf(applied.resultingVersion()));
		} else if (input.outcome() instanceof CommandOutcome.Rejected rejected) {
			outcome = "REJECTED";
			code = new JsonString(rejected.rejectionCode());
		} else {
			outcome = "FAILED";
			code = new JsonString(((CommandOutcome.Failed) input.outcome()).publicFailureCode());
		}
		JsonObject payload = new JsonObject(Map.of(
				"commandId", new JsonString(commandId),
				"submittedByUserId", new JsonString(input.submittedByUserId().toString()),
				"outcome", new JsonString(outcome),
				"potId", potId,
				"resultingVersion", version,
				"code", code,
				"resolvedAt", new JsonString(input.outcome().resolvedAt().toString())));
		return new Projection(key, List.of(new ProjectionArtifact(
				CommandResultProjectionDefinition.RESULT, new ArtifactKey(commandId), payload)));
	}
}
