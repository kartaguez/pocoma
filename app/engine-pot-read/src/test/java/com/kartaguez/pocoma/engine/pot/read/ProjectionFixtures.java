package com.kartaguez.pocoma.engine.pot.read;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

final class ProjectionFixtures {
	static final UUID POT_UUID = UUID.fromString("00000000-0000-0000-0000-000000000100");
	static final UUID CREATOR_UUID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000102");
	static final UUID OTHER_USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000103");
	static final UUID SHAREHOLDER_A_UUID = UUID.fromString("00000000-0000-0000-0000-000000000201");
	static final UUID SHAREHOLDER_B_UUID = UUID.fromString("00000000-0000-0000-0000-000000000202");
	static final UUID EXPENSE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000301");
	static final long VERSION = 7;

	private ProjectionFixtures() {
	}

	static ProjectionKey authKey() {
		return new ProjectionKey(AuthProjectionDefinition.PROJECTION_TYPE,
				AuthProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(POT_UUID.toString()), VERSION);
	}

	static ProjectionKey readPotKey() {
		return new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(POT_UUID.toString()), VERSION);
	}

	static ValidatedProjection authProjection(UUID creator, List<ProjectionArtifact> associations) {
		return authProjection(creator, creator, associations);
	}

	static ValidatedProjection authProjection(UUID creatorKey, UUID creatorPayload,
			List<ProjectionArtifact> associations) {
		var artifacts = new java.util.ArrayList<ProjectionArtifact>();
		artifacts.add(new ProjectionArtifact(AuthProjectionDefinition.CREATOR,
				new ArtifactKey(creatorKey.toString()), object(Map.of("userId", string(creatorPayload)))));
		artifacts.addAll(associations);
		return validated(AuthProjectionDefinition.DEFINITION, new Projection(authKey(), artifacts));
	}

	static ProjectionArtifact association(UUID userId, UUID shareholderId) {
		return new ProjectionArtifact(AuthProjectionDefinition.SHAREHOLDER_USER,
				new ArtifactKey(userId.toString()), object(Map.of(
						"userId", string(userId),
						"shareholderId", string(shareholderId))));
	}

	static ProjectionArtifact associationWithKey(UUID key, UUID userId, UUID shareholderId) {
		return new ProjectionArtifact(AuthProjectionDefinition.SHAREHOLDER_USER,
				new ArtifactKey(key.toString()), object(Map.of(
						"userId", string(userId),
						"shareholderId", string(shareholderId))));
	}

	static ValidatedProjection readPotProjection(List<ProjectionArtifact> artifacts) {
		return validated(ReadPotProjectionDefinition.DEFINITION, new Projection(readPotKey(), artifacts));
	}

	static ProjectionArtifact pot() {
		return pot(POT_UUID, POT_UUID);
	}

	static ProjectionArtifact pot(UUID artifactKey, UUID payloadId) {
		return new ProjectionArtifact(ReadPotProjectionDefinition.POT, new ArtifactKey(artifactKey.toString()),
				object(Map.of("potId", string(payloadId), "name", new JsonString("Trip"))));
	}

	static ProjectionArtifact shareholder(UUID id, String name, JsonValue userId, long numerator, long denominator) {
		return new ProjectionArtifact(ReadPotProjectionDefinition.SHAREHOLDER, new ArtifactKey(id.toString()),
				object(Map.of(
						"shareholderId", string(id),
						"name", new JsonString(name),
						"userId", userId,
						"part", fraction(numerator, denominator))));
	}

	static ProjectionArtifact expense(UUID payerId, List<JsonValue> shares) {
		return expense(EXPENSE_UUID, EXPENSE_UUID, payerId, shares);
	}

	static ProjectionArtifact expense(UUID artifactKey, UUID payloadId, UUID payerId, List<JsonValue> shares) {
		return new ProjectionArtifact(ReadPotProjectionDefinition.EXPENSE, new ArtifactKey(artifactKey.toString()),
				object(Map.of(
						"expenseId", string(payloadId),
						"name", new JsonString("Dinner"),
						"amount", fraction(12345, 100),
						"date", new JsonString("2026-09-19"),
						"payerShareholderId", string(payerId),
						"shares", new JsonArray(shares))));
	}

	static JsonValue share(UUID shareholderId, long numerator, long denominator) {
		return object(Map.of(
				"shareholderId", string(shareholderId),
				"part", fraction(numerator, denominator)));
	}

	static JsonString string(UUID value) {
		return new JsonString(value.toString());
	}

	static JsonNull jsonNull() {
		return JsonNull.INSTANCE;
	}

	static JsonObject fraction(long numerator, long denominator) {
		return object(Map.of(
				"numerator", new JsonNumber(BigDecimal.valueOf(numerator)),
				"denominator", new JsonNumber(BigDecimal.valueOf(denominator))));
	}

	private static JsonObject object(Map<String, JsonValue> values) {
		return new JsonObject(values);
	}

	private static ValidatedProjection validated(ProjectionDefinition definition, Projection projection) {
		return new ProjectionValidator((schema, payload) -> true).validate(definition, projection);
	}
}
