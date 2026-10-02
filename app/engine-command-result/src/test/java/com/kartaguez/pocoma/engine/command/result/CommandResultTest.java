package com.kartaguez.pocoma.engine.command.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.port.in.projection.read.ProjectionReadResult;

class CommandResultTest {
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID POT_ID = UUID.randomUUID();
	private static final ExternalIdentity IDENTITY = new ExternalIdentity("issuer", "subject");
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final ProjectionKey KEY = new ProjectionKey(
			CommandResultProjectionDefinition.PROJECTION_TYPE,
			CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
			new TargetObjectId(COMMAND_ID.value().toString()), 1);

	@Test
	void projectsTheThreeTerminalOutcomesFromAuthoritativeInputs() {
		for (CommandResultVisibility visibility : List.of(new CommandResultVisibility(IDENTITY))) {
			assertPayload(new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW), visibility,
					"APPLIED", POT_ID.toString(), 7L, null);
			assertPayload(new CommandOutcome.Rejected(COMMAND_ID, "POT_VERSION_CONFLICT", NOW), visibility,
					"REJECTED", null, null, "POT_VERSION_CONFLICT");
			assertPayload(new CommandOutcome.Failed(COMMAND_ID, CommandOutcome.PUBLIC_FAILURE_CODE, NOW), visibility,
					"FAILED", null, null, CommandOutcome.PUBLIC_FAILURE_CODE);
		}
	}

	@Test
	void projectsAndAuthorizesTargetV2ByExactExternalIdentityWithoutBindingLookup() {
		CommandResultVisibility visibility = new CommandResultVisibility(IDENTITY);
		Projection projection = new CommandResultProjector().project(KEY,
				new CommandResultProjectionInput(new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW), visibility));
		JsonObject payload = assertInstanceOf(JsonObject.class, projection.artifacts().getFirst().payload());
		assertEquals(new JsonString("EXACT_EXTERNAL_IDENTITY"), payload.values().get("visibility"));
		JsonObject visibleTo = assertInstanceOf(JsonObject.class, payload.values().get("visibleToExternalIdentity"));
		assertEquals(new JsonString("issuer"), visibleTo.values().get("issuer"));
		assertEquals(new JsonString("subject"), visibleTo.values().get("subject"));
		assertEquals(null, payload.values().get("submittedByUserId"));

		ValidatedProjection ready = new ProjectionValidator((schema, value) -> true)
				.validate(CommandResultProjectionDefinition.DEFINITION, projection);
		var service = service(new ProjectionReadResult.Ready(ready));
		assertInstanceOf(GetCommandResult.Applied.class, service.get(COMMAND_ID, IDENTITY));
		assertInstanceOf(GetCommandResult.NotFound.class,
				service.get(COMMAND_ID, new ExternalIdentity("other-issuer", "subject")));
		assertInstanceOf(GetCommandResult.NotFound.class,
				service.get(COMMAND_ID, new ExternalIdentity("issuer", "other-subject")));
	}

	@Test
	void enforcesCommandIdentityAndSingleTerminalProjectionVersion() {
		var projector = new CommandResultProjector();
		var input = new CommandResultProjectionInput(
				new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW), new CommandResultVisibility(IDENTITY));
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
		assertInstanceOf(GetCommandResult.NotFound.class, service(new ProjectionReadResult.NotReady(KEY))
				.get(COMMAND_ID, IDENTITY));
		assertInstanceOf(GetCommandResult.NotFound.class, service(new ProjectionReadResult.Failed(KEY))
				.get(COMMAND_ID, IDENTITY));

		ValidatedProjection ready = validated(new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW));
		GetCommandResult.Applied applied = assertInstanceOf(GetCommandResult.Applied.class,
				service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID, IDENTITY));
		assertEquals(POT_ID, applied.potId());
		assertEquals(7, applied.resultingVersion());
		assertInstanceOf(GetCommandResult.NotFound.class,
				service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID,
						new ExternalIdentity("issuer", "someone-else")));

		GetCommandResult.Rejected rejected = assertInstanceOf(GetCommandResult.Rejected.class,
				service(new ProjectionReadResult.Ready(validated(new CommandOutcome.Rejected(
						COMMAND_ID, "POT_VERSION_CONFLICT", NOW)))).get(COMMAND_ID, IDENTITY));
		assertEquals("POT_VERSION_CONFLICT", rejected.code());

		GetCommandResult.Failed failed = assertInstanceOf(GetCommandResult.Failed.class,
				service(new ProjectionReadResult.Ready(validated(new CommandOutcome.Failed(
						COMMAND_ID, CommandOutcome.PUBLIC_FAILURE_CODE, NOW)))).get(COMMAND_ID, IDENTITY));
		assertEquals(CommandOutcome.PUBLIC_FAILURE_CODE, failed.code());
	}


	@Test
	void invalidReadyPayloadIsNotVisible() {
		Projection invalid = new Projection(KEY, List.of(new ProjectionArtifact(
				CommandResultProjectionDefinition.RESULT,
				new ArtifactKey(COMMAND_ID.value().toString()), JsonNull.INSTANCE)));
		ValidatedProjection ready = new ProjectionValidator((schema, payload) -> true)
				.validate(CommandResultProjectionDefinition.DEFINITION, invalid);

		assertThrows(IllegalStateException.class,
				() -> service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID, IDENTITY));
	}

	@Test
	void rejectsMixedLegacyAndExactIdentityVisibilityAsAnInvariantFailure() {
		Projection exact = new CommandResultProjector().project(KEY, new CommandResultProjectionInput(
				new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW),
				new CommandResultVisibility(IDENTITY)));
		JsonObject exactPayload = assertInstanceOf(JsonObject.class, exact.artifacts().getFirst().payload());
		var mixedValues = new HashMap<>(exactPayload.values());
		mixedValues.put("submittedByUserId", new JsonString(USER_ID.toString()));
		Projection mixed = new Projection(KEY, List.of(new ProjectionArtifact(
				CommandResultProjectionDefinition.RESULT,
				new ArtifactKey(COMMAND_ID.value().toString()), new JsonObject(mixedValues))));
		ValidatedProjection ready = new ProjectionValidator((schema, payload) -> true)
				.validate(CommandResultProjectionDefinition.DEFINITION, mixed);

		assertThrows(IllegalStateException.class,
				() -> service(new ProjectionReadResult.Ready(ready)).get(COMMAND_ID, IDENTITY));
	}

	@Test
	void rejectsAReadyResultWhoseDurableIdentityDisagreesWithItsRequestedKey() {
		Projection exact = new CommandResultProjector().project(KEY, new CommandResultProjectionInput(
				new CommandOutcome.Applied(COMMAND_ID, POT_ID, 7, NOW),
				new CommandResultVisibility(IDENTITY)));
		JsonObject payload = assertInstanceOf(JsonObject.class, exact.artifacts().getFirst().payload());
		var wrongPayload = new HashMap<>(payload.values());
		wrongPayload.put("commandId", new JsonString(UUID.randomUUID().toString()));
		Projection divergentPayload = new Projection(KEY, List.of(new ProjectionArtifact(
				CommandResultProjectionDefinition.RESULT, new ArtifactKey(COMMAND_ID.value().toString()),
				new JsonObject(wrongPayload))));
		Projection divergentArtifactKey = new Projection(KEY, List.of(new ProjectionArtifact(
				CommandResultProjectionDefinition.RESULT, new ArtifactKey(UUID.randomUUID().toString()), payload)));
		var permissiveValidator = new ProjectionValidator((schema, value) -> true);
		assertThrows(IllegalStateException.class, () -> service(new ProjectionReadResult.Ready(
				permissiveValidator.validate(CommandResultProjectionDefinition.DEFINITION, divergentPayload)))
				.get(COMMAND_ID, IDENTITY));
		assertThrows(IllegalStateException.class, () -> service(new ProjectionReadResult.Ready(
				permissiveValidator.validate(CommandResultProjectionDefinition.DEFINITION, divergentArtifactKey)))
				.get(COMMAND_ID, IDENTITY));
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
				KEY, new CommandResultProjectionInput(outcome, new CommandResultVisibility(IDENTITY)));
		return new ProjectionValidator((schema, payload) -> true)
				.validate(CommandResultProjectionDefinition.DEFINITION, projection);
	}

	private static void assertPayload(CommandOutcome outcome, CommandResultVisibility visibility, String expectedOutcome,
			String expectedPotId, Long expectedVersion, String expectedCode) {
		Projection projection = new CommandResultProjector().project(
				KEY, new CommandResultProjectionInput(outcome, visibility));
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
