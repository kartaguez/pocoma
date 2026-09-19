package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProjectionValidatorTest {
	private static final ProjectionType TYPE = new ProjectionType("READ_POT");
	private static final TargetObjectType TARGET_TYPE = new TargetObjectType("POT");
	private static final ArtifactType HEADER = new ArtifactType("HEADER");
	private static final JsonValue SCHEMA = new JsonObject(java.util.Map.of("type", new JsonString("object")));
	private static final JsonValue PAYLOAD = new JsonObject(java.util.Map.of("label", new JsonString("Trip")));

	@Test
	void rejectsAProjectionTypeThatDoesNotMatchTheDefinition() {
		assertInvalid(definition(new Cardinality(1, 1)),
				projection(new ProjectionType("OTHER"), TARGET_TYPE, List.of(artifact(HEADER, "main", PAYLOAD))));
	}

	@Test
	void rejectsATargetObjectTypeThatDoesNotMatchTheDefinition() {
		assertInvalid(definition(new Cardinality(1, 1)),
				projection(TYPE, new TargetObjectType("USER"), List.of(artifact(HEADER, "main", PAYLOAD))));
	}

	@Test
	void rejectsAnArtifactTypeThatIsNotDeclared() {
		assertInvalid(definition(new Cardinality(0, 1)),
				projection(TYPE, TARGET_TYPE, List.of(artifact(new ArtifactType("UNKNOWN"), "main", PAYLOAD))));
	}

	@Test
	void rejectsCardinalitiesBelowTheMinimumAndAboveTheMaximum() {
		assertInvalid(definition(new Cardinality(1, 1)), projection(TYPE, TARGET_TYPE, List.of()));
		assertInvalid(definition(new Cardinality(0, 1)), projection(TYPE, TARGET_TYPE, List.of(
				artifact(HEADER, "first", PAYLOAD), artifact(HEADER, "second", PAYLOAD))));
	}

	@Test
	void rejectsDuplicateKeysWithinTheSameArtifactType() {
		assertInvalid(definition(new Cardinality(0, null)), projection(TYPE, TARGET_TYPE, List.of(
				artifact(HEADER, "same", PAYLOAD), artifact(HEADER, "same", PAYLOAD))));
	}

	@Test
	void delegatesEachSchemaPayloadPairAndRejectsAnInvalidPayload() {
		var calls = new ArrayList<List<JsonValue>>();
		var validator = new ProjectionValidator((schema, payload) -> {
			calls.add(List.of(schema, payload));
			return false;
		});
		var projection = projection(TYPE, TARGET_TYPE, List.of(artifact(HEADER, "main", PAYLOAD)));

		assertThrows(ProjectionValidationException.class,
				() -> validator.validate(definition(new Cardinality(1, 1)), projection));
		assertEquals(List.of(List.of(SCHEMA, PAYLOAD)), calls);
	}

	@Test
	void createsAProofOnlyAfterAllChecksSucceed() {
		var calls = new ArrayList<List<JsonValue>>();
		var validator = new ProjectionValidator((schema, payload) -> {
			calls.add(List.of(schema, payload));
			return true;
		});
		var projection = projection(TYPE, TARGET_TYPE, List.of(artifact(HEADER, "main", PAYLOAD)));

		var validated = validator.validate(definition(new Cardinality(1, 1)), projection);
		assertSame(projection, validated.projection());
		assertEquals(List.of(List.of(SCHEMA, PAYLOAD)), calls);
	}

	@Test
	void validatedProjectionHasNoPublicConstructor() {
		assertFalse(List.of(ValidatedProjection.class.getDeclaredConstructors()).stream()
				.anyMatch(constructor -> Modifier.isPublic(constructor.getModifiers())));
	}

	private static void assertInvalid(ProjectionDefinition definition, Projection projection) {
		assertThrows(ProjectionValidationException.class,
				() -> new ProjectionValidator((schema, payload) -> true).validate(definition, projection));
	}

	private static ProjectionDefinition definition(Cardinality cardinality) {
		return new ProjectionDefinition(TYPE, TARGET_TYPE,
				List.of(new ArtifactDefinition(HEADER, cardinality, SCHEMA)));
	}

	private static Projection projection(ProjectionType type, TargetObjectType targetType,
			List<ProjectionArtifact> artifacts) {
		return new Projection(new ProjectionKey(type, targetType, new TargetObjectId("pot-42"), 3), artifacts);
	}

	private static ProjectionArtifact artifact(ArtifactType type, String key, JsonValue payload) {
		return new ProjectionArtifact(type, new ArtifactKey(key), payload);
	}
}
