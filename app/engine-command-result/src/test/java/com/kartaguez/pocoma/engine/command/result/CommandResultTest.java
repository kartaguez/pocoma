package com.kartaguez.pocoma.engine.command.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

class CommandResultTest {
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID POT_ID = UUID.randomUUID();
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final ProjectionKey KEY = new ProjectionKey(
			CommandResultProjectionDefinition.PROJECTION_TYPE,
			CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
			new TargetObjectId(COMMAND_ID.value().toString()), 1);

	@Test
	void projectsTheThreeTerminalOutcomesFromAuthoritativeInputs() {
		assertPayload(new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW),
				"APPLIED", POT_ID.toString(), 7L, null);
		assertPayload(new CommandOutcome.Rejected(COMMAND_ID, "POT_VERSION_CONFLICT", NOW),
				"REJECTED", null, null, "POT_VERSION_CONFLICT");
		assertPayload(new CommandOutcome.Failed(COMMAND_ID, CommandOutcome.PUBLIC_FAILURE_CODE, NOW),
				"FAILED", null, null, CommandOutcome.PUBLIC_FAILURE_CODE);
	}

	@Test
	void enforcesCommandIdentityAndSingleTerminalProjectionVersion() {
		var projector = new CommandResultProjector();
		var input = new CommandResultProjectionInput(
				new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW), USER_ID);
		assertThrows(IllegalStateException.class, () -> projector.project(new ProjectionKey(
				CommandResultProjectionDefinition.PROJECTION_TYPE,
				CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(UUID.randomUUID().toString()), 1), input));
		assertThrows(IllegalStateException.class, () -> projector.project(new ProjectionKey(
				CommandResultProjectionDefinition.PROJECTION_TYPE,
				CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(COMMAND_ID.value().toString()), 2), input));
	}

	@Test
	void exactReadMapsAvailabilityFailureOwnershipAndTerminalVariants() {
		assertInstanceOf(GetCommandResult.NotReady.class, service(new ProjectionReadResult.NotReady(KEY))
				.get(COMMAND_ID, USER_ID));
		assertInstanceOf(GetCommandResult.ProjectionFailed.class, service(new ProjectionReadResult.Failed(KEY))
				.get(COMMAND_ID, USER_ID));

		ValidatedProjection ready = validated(new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW));
		GetCommandResult.Applied applied = assertInstanceOf(GetCommandResult.Applied.class,
				service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID, USER_ID));
		assertEquals(POT_ID, applied.potId());
		assertEquals(7, applied.resultingVersion());
		assertInstanceOf(GetCommandResult.NotFound.class,
				service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID, UUID.randomUUID()));
	}

	private static GetCommandResultService service(ProjectionReadResult result) {
		return new GetCommandResultService((key, definition) -> {
			assertEquals(KEY, key);
			assertEquals(CommandResultProjectionDefinition.DEFINITION, definition);
			return result;
		});
	}

	private static ValidatedProjection validated(CommandOutcome outcome) {
		Projection projection = new CommandResultProjector().project(
				KEY, new CommandResultProjectionInput(outcome, USER_ID));
		return new ProjectionValidator((schema, payload) -> true)
				.validate(CommandResultProjectionDefinition.DEFINITION, projection);
	}

	private static void assertPayload(CommandOutcome outcome, String expectedOutcome,
			String expectedPotId, Long expectedVersion, String expectedCode) {
		Projection projection = new CommandResultProjector().project(
				KEY, new CommandResultProjectionInput(outcome, USER_ID));
		JsonObject payload = assertInstanceOf(JsonObject.class,
				projection.artifacts().getFirst().payload());
		assertEquals(new JsonString(expectedOutcome), payload.values().get("outcome"));
		assertEquals(expectedPotId == null ? JsonNull.INSTANCE : new JsonString(expectedPotId),
				payload.values().get("potId"));
		assertEquals(expectedVersion == null ? JsonNull.INSTANCE
				: new JsonNumber(java.math.BigDecimal.valueOf(expectedVersion)),
				payload.values().get("resultingVersion"));
		assertEquals(expectedCode == null ? JsonNull.INSTANCE : new JsonString(expectedCode),
				payload.values().get("code"));
	}
}
