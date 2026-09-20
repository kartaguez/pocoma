package com.kartaguez.pocoma.engine.pot.read;

import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidationException;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;

class ProjectionDefinitionsTest {
	private static final String ID = "00000000-0000-0000-0000-000000000001";

	@Test
	void authDefinitionDeclaresCanonicalTypesAndCardinalities() {
		var definition = AuthProjectionDefinition.DEFINITION;

		assertEquals("AUTH", definition.projectionType().value());
		assertEquals("POT", definition.targetObjectType().value());
		assertEquals(List.of("CREATOR", "SHAREHOLDER_USER"), definition.artifactDefinitions().stream()
				.map(value -> value.artifactType().value()).toList());
		assertEquals(1, artifact(definition.artifactDefinitions(), "CREATOR").cardinality().min());
		assertEquals(1, artifact(definition.artifactDefinitions(), "CREATOR").cardinality().max());
		assertEquals(0, artifact(definition.artifactDefinitions(), "SHAREHOLDER_USER").cardinality().min());
		assertNull(artifact(definition.artifactDefinitions(), "SHAREHOLDER_USER").cardinality().max());
	}

	@Test
	void readPotDefinitionRequiresCanonicalNullableUserIdAndAllowsEmptyShares() {
		var definition = ReadPotProjectionDefinition.DEFINITION;
		assertEquals(List.of("POT", "SHAREHOLDER", "EXPENSE"), definition.artifactDefinitions().stream()
				.map(value -> value.artifactType().value()).toList());

		JsonObject shareholderSchema = object(artifact(definition.artifactDefinitions(), "SHAREHOLDER").schema());
		assertTrue(requiredFields(shareholderSchema).contains("userId"));
		JsonObject userIdSchema = object(object(shareholderSchema.values().get("properties")).values().get("userId"));
		assertEquals(2, array(userIdSchema.values().get("anyOf")).values().size());

		JsonObject expenseSchema = object(artifact(definition.artifactDefinitions(), "EXPENSE").schema());
		JsonObject sharesSchema = object(object(expenseSchema.values().get("properties")).values().get("shares"));
		assertFalse(sharesSchema.values().containsKey("minItems"));
	}

	@Test
	void fractionSchemasRequireNonNegativeNumeratorAndPositiveDenominatorWithoutLegacyMaximum() {
		JsonObject shareholderSchema = object(artifact(ReadPotProjectionDefinition.DEFINITION.artifactDefinitions(),
				"SHAREHOLDER").schema());
		JsonObject shareholderProperties = object(shareholderSchema.values().get("properties"));
		assertNonNegativeFraction(object(shareholderProperties.values().get("part")));

		JsonObject expenseSchema = object(artifact(ReadPotProjectionDefinition.DEFINITION.artifactDefinitions(),
				"EXPENSE").schema());
		JsonObject expenseProperties = object(expenseSchema.values().get("properties"));
		assertNonNegativeFraction(object(expenseProperties.values().get("amount")));
		JsonObject shareItem = object(object(expenseProperties.values().get("shares")).values().get("items"));
		assertNonNegativeFraction(object(object(shareItem.values().get("properties")).values().get("part")));
	}

	@Test
	void deterministicSchemaBoundaryRejectsInvalidFractionBoundsAndAcceptsZero() {
		ArtifactDefinition shareholder = artifact(ReadPotProjectionDefinition.DEFINITION.artifactDefinitions(),
				"SHAREHOLDER");
		var validator = new ProjectionValidator((schema, payload) -> !schema.equals(shareholder.schema())
				|| payload instanceof JsonObject object && isNonNegativeFraction(object.values().get("part")));

		assertThrows(ProjectionValidationException.class,
				() -> validator.validate(ReadPotProjectionDefinition.DEFINITION,
						projection(shareholderPayload(JsonNull.INSTANCE, -1, 1))));
		assertThrows(ProjectionValidationException.class,
				() -> validator.validate(ReadPotProjectionDefinition.DEFINITION,
						projection(shareholderPayload(JsonNull.INSTANCE, 1, 0))));
		assertThrows(ProjectionValidationException.class,
				() -> validator.validate(ReadPotProjectionDefinition.DEFINITION,
						projection(shareholderPayload(JsonNull.INSTANCE, 1, -1))));
		validator.validate(ReadPotProjectionDefinition.DEFINITION,
				projection(shareholderPayload(JsonNull.INSTANCE, 0, 1)));
	}

	@Test
	void projectionValidatorBoundaryRejectsMissingShareholderUserIdAndAcceptsUuidOrNull() {
		ArtifactDefinition shareholder = artifact(ReadPotProjectionDefinition.DEFINITION.artifactDefinitions(),
				"SHAREHOLDER");
		var validator = new ProjectionValidator((schema, payload) -> {
			if (!schema.equals(shareholder.schema()) || !(payload instanceof JsonObject object)) {
				return true;
			}
			JsonValue userId = object.values().get("userId");
			return userId == JsonNull.INSTANCE
					|| userId instanceof JsonString string && isUuid(string.value());
		});

		assertThrows(ProjectionValidationException.class,
				() -> validator.validate(ReadPotProjectionDefinition.DEFINITION,
						projection(shareholderPayloadWithoutUserId())));
		validator.validate(ReadPotProjectionDefinition.DEFINITION,
				projection(shareholderPayload(JsonNull.INSTANCE)));
		validator.validate(ReadPotProjectionDefinition.DEFINITION,
				projection(shareholderPayload(new JsonString(ID))));
	}

	private static Projection projection(JsonValue shareholderPayload) {
		ProjectionKey key = new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(ID), 1);
		return new Projection(key, List.of(
				new ProjectionArtifact(ReadPotProjectionDefinition.POT, new ArtifactKey(ID),
						new JsonObject(Map.of("potId", new JsonString(ID), "name", new JsonString("Trip")))),
				new ProjectionArtifact(ReadPotProjectionDefinition.SHAREHOLDER, new ArtifactKey(ID),
						shareholderPayload)));
	}

	private static JsonObject shareholderPayload(JsonValue userId) {
		return shareholderPayload(userId, 1, 3);
	}

	private static JsonObject shareholderPayload(JsonValue userId, long numerator, long denominator) {
		return new JsonObject(Map.of(
				"shareholderId", new JsonString(ID),
				"name", new JsonString("Alice"),
				"userId", userId,
				"part", fraction(numerator, denominator)));
	}

	private static JsonObject shareholderPayloadWithoutUserId() {
		return new JsonObject(Map.of(
				"shareholderId", new JsonString(ID),
				"name", new JsonString("Alice"),
				"part", fraction(1, 3)));
	}

	private static JsonObject fraction(long numerator, long denominator) {
		return new JsonObject(Map.of(
				"numerator", new JsonNumber(BigDecimal.valueOf(numerator)),
				"denominator", new JsonNumber(BigDecimal.valueOf(denominator))));
	}

	private static boolean isUuid(String value) {
		try {
			UUID.fromString(value);
			return true;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static ArtifactDefinition artifact(List<ArtifactDefinition> definitions, String type) {
		return definitions.stream().filter(definition -> definition.artifactType().value().equals(type))
				.findFirst().orElseThrow();
	}

	private static JsonObject object(JsonValue value) {
		return assertInstanceOf(JsonObject.class, value);
	}

	private static JsonArray array(JsonValue value) {
		return assertInstanceOf(JsonArray.class, value);
	}

	private static List<String> requiredFields(JsonObject schema) {
		return array(schema.values().get("required")).values().stream()
				.map(JsonString.class::cast).map(JsonString::value).toList();
	}

	private static BigDecimal number(JsonObject object, String field) {
		return assertInstanceOf(JsonNumber.class, object.values().get(field)).value();
	}

	private static void assertNonNegativeFraction(JsonObject fractionSchema) {
		JsonObject properties = object(fractionSchema.values().get("properties"));
		assertEquals(BigDecimal.ZERO, number(object(properties.values().get("numerator")), "minimum"));
		assertEquals(BigDecimal.ONE, number(object(properties.values().get("denominator")), "minimum"));
		assertEquals(BigDecimal.valueOf(Long.MAX_VALUE),
				number(object(properties.values().get("numerator")), "maximum"));
		assertEquals(BigDecimal.valueOf(Long.MAX_VALUE),
				number(object(properties.values().get("denominator")), "maximum"));
	}

	private static boolean isNonNegativeFraction(JsonValue value) {
		if (!(value instanceof JsonObject fraction)) {
			return false;
		}
		JsonValue numerator = fraction.values().get("numerator");
		JsonValue denominator = fraction.values().get("denominator");
		return numerator instanceof JsonNumber numeratorNumber
				&& denominator instanceof JsonNumber denominatorNumber
				&& numeratorNumber.value().compareTo(BigDecimal.ZERO) >= 0
				&& denominatorNumber.value().compareTo(BigDecimal.ZERO) > 0;
	}
}
