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

	private static JsonString uuid(String value) {
		return new JsonString(value);
	}

	private static JsonObject fraction(long numerator, long denominator) {
		return new JsonObject(Map.of(
				"numerator", new JsonNumber(BigDecimal.valueOf(numerator)),
				"denominator", new JsonNumber(BigDecimal.valueOf(denominator))));
	}
}
