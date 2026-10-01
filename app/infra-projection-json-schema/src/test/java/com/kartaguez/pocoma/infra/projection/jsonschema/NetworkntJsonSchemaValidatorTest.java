package com.kartaguez.pocoma.infra.projection.jsonschema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionDefinition;

class NetworkntJsonSchemaValidatorTest {
	private final NetworkntJsonSchemaValidator validator = new NetworkntJsonSchemaValidator(new ObjectMapper());

	@Test
	void validatesRequiredNullableUuidAndStrictObjects() {
		var uuid = new JsonObject(Map.of("type", new JsonString("string"), "format", new JsonString("uuid")));
		var schema = new JsonObject(Map.of(
				"type", new JsonString("object"),
				"properties", new JsonObject(Map.of("userId", new JsonObject(Map.of("anyOf",
						new JsonArray(List.of(uuid, new JsonObject(Map.of("type", new JsonString("null"))))))))),
				"required", new JsonArray(List.of(new JsonString("userId"))),
				"additionalProperties", new JsonBoolean(false)));

		assertTrue(validator.isValid(schema, new JsonObject(Map.of("userId", JsonNull.INSTANCE))));
		assertTrue(validator.isValid(schema, new JsonObject(Map.of("userId",
				new JsonString("00000000-0000-4000-8000-000000000001")))));
		assertFalse(validator.isValid(schema, new JsonObject(Map.of())));
		assertFalse(validator.isValid(schema, new JsonObject(Map.of("userId", new JsonString("not-a-uuid")))));
	}

	@Test
	void validatesTheConcreteReadPotAndSignedPotBalancesSchemas() {
		var shareholderSchema = ReadPotProjectionDefinition.DEFINITION.artifactDefinitions().stream()
				.filter(definition -> definition.artifactType().equals(ReadPotProjectionDefinition.SHAREHOLDER))
				.findFirst().orElseThrow().schema();
		var shareholder = new JsonObject(Map.of(
				"shareholderId", uuid("20000000-0000-4000-8000-000000000001"),
				"name", new JsonString("Alice"),
				"userId", JsonNull.INSTANCE,
				"part", fraction(0, 7)));
		assertTrue(validator.isValid(shareholderSchema, shareholder));
		assertFalse(validator.isValid(shareholderSchema, new JsonObject(Map.of(
				"shareholderId", uuid("20000000-0000-4000-8000-000000000001"),
				"name", new JsonString("Alice"),
				"part", fraction(1, 3)))));

		var balanceSchema = PotBalancesProjectionDefinition.DEFINITION.artifactDefinitions().getFirst().schema();
		var signedBalance = new JsonObject(Map.of(
				"shareholderId", uuid("20000000-0000-4000-8000-000000000001"),
				"balance", fraction(-2, 7)));
		assertTrue(validator.isValid(balanceSchema, signedBalance));
		assertFalse(validator.isValid(balanceSchema, new JsonObject(Map.of(
				"shareholderId", uuid("20000000-0000-4000-8000-000000000001"),
				"balance", fraction(-2, 0)))));
	}

	@Test
	void commandResultSchemaAcceptsOnlyTheHistoricalV1OrDiscriminatedExactV2Shape() {
		var schema = CommandResultProjectionDefinition.DEFINITION.artifactDefinitions().getFirst().schema();
		var common = new java.util.HashMap<String, com.kartaguez.pocoma.domain.projection.JsonValue>();
		common.put("commandId", uuid("10000000-0000-4000-8000-000000000001"));
		common.put("outcome", new JsonString("APPLIED"));
		common.put("potId", uuid("20000000-0000-4000-8000-000000000001"));
		common.put("resultingVersion", new JsonNumber(BigDecimal.ONE));
		common.put("code", JsonNull.INSTANCE);
		common.put("resolvedAt", new JsonString("2026-10-01T10:00:00Z"));

		var v1 = new java.util.HashMap<>(common);
		v1.put("submittedByUserId", uuid("30000000-0000-4000-8000-000000000001"));
		assertTrue(validator.isValid(schema, new JsonObject(v1)), "historical V1 payload must remain valid");

		var exactIdentity = new JsonObject(Map.of(
				"issuer", new JsonString("https://issuer.example"),
				"subject", new JsonString("subject-1")));
		var v2 = new java.util.HashMap<>(common);
		v2.put("visibility", new JsonString("EXACT_EXTERNAL_IDENTITY"));
		v2.put("visibleToExternalIdentity", exactIdentity);
		assertTrue(validator.isValid(schema, new JsonObject(v2)));

		var mixedV1 = new java.util.HashMap<>(v1);
		mixedV1.put("visibility", new JsonString("EXACT_EXTERNAL_IDENTITY"));
		mixedV1.put("visibleToExternalIdentity", exactIdentity);
		assertFalse(validator.isValid(schema, new JsonObject(mixedV1)));

		var mixedV2 = new java.util.HashMap<>(v2);
		mixedV2.put("submittedByUserId", uuid("30000000-0000-4000-8000-000000000001"));
		assertFalse(validator.isValid(schema, new JsonObject(mixedV2)));

		var missingDiscriminator = new java.util.HashMap<>(common);
		missingDiscriminator.put("visibleToExternalIdentity", exactIdentity);
		assertFalse(validator.isValid(schema, new JsonObject(missingDiscriminator)));

		var incompleteV2 = new java.util.HashMap<>(common);
		incompleteV2.put("visibility", new JsonString("EXACT_EXTERNAL_IDENTITY"));
		assertFalse(validator.isValid(schema, new JsonObject(incompleteV2)));
		assertFalse(validator.isValid(schema, new JsonObject(common)));
	}

	private static JsonString uuid(String value) {
		return new JsonString(value);
	}

	private static JsonObject fraction(long numerator, long denominator) {
		return new JsonObject(Map.of(
				"numerator", new JsonNumber(BigDecimal.valueOf(numerator)),
				"denominator", new JsonNumber(BigDecimal.valueOf(denominator))));
	}
}
