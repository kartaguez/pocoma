package com.kartaguez.pocoma.engine.projection.pot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
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
import com.kartaguez.pocoma.engine.pot.read.ReadPotProjectionDefinition;

public final class ReadPotProjector {
	public Projection project(ProjectionKey key, ReadPotProjectionInput input) {
		if (!key.projectionType().equals(ReadPotProjectionDefinition.PROJECTION_TYPE)
				|| !key.targetObjectType().equals(ReadPotProjectionDefinition.TARGET_OBJECT_TYPE)
				|| !key.targetObjectId().value().equals(input.potId().value().toString())
				|| key.targetVersion() != input.version()) {
			throw new IllegalStateException("READ_POT input does not match requested key");
		}
		var artifacts = new ArrayList<ProjectionArtifact>();
		artifacts.add(new ProjectionArtifact(ReadPotProjectionDefinition.POT,
				new ArtifactKey(input.potId().value().toString()), object(Map.of(
						"potId", string(input.potId().value()), "name", new JsonString(input.name().value())))));
		input.shareholders().forEach(shareholder -> artifacts.add(new ProjectionArtifact(
				ReadPotProjectionDefinition.SHAREHOLDER, new ArtifactKey(shareholder.id().value().toString()),
				object(Map.of("shareholderId", string(shareholder.id().value()),
						"name", new JsonString(shareholder.name().value()),
						"userId", shareholder.userId().<JsonValue>map(id -> string(id.value())).orElse(JsonNull.INSTANCE),
						"part", fraction(shareholder.part().value()))))));
		input.expenses().forEach(expense -> artifacts.add(new ProjectionArtifact(
				ReadPotProjectionDefinition.EXPENSE, new ArtifactKey(expense.id().value().toString()),
				object(Map.of("expenseId", string(expense.id().value()), "name", new JsonString(expense.name().value()),
						"amount", fraction(expense.amount().value()), "date", new JsonString(expense.date().toString()),
						"payerShareholderId", string(expense.payerId().value()),
						"shares", new JsonArray(expense.shares().stream().map(share -> (JsonValue) object(Map.of(
								"shareholderId", string(share.shareholderId().value()),
								"part", fraction(share.part().value())))).toList()))))));
		return new Projection(key, artifacts);
	}

	private static JsonObject fraction(Fraction value) {
		return object(Map.of("numerator", number(value.numerator()), "denominator", number(value.denominator())));
	}
	private static JsonObject object(Map<String, JsonValue> values) { return new JsonObject(values); }
	private static JsonString string(java.util.UUID value) { return new JsonString(value.toString()); }
	private static JsonNumber number(long value) { return new JsonNumber(BigDecimal.valueOf(value)); }
}
