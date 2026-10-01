package com.kartaguez.pocoma.engine.projection.pot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;

class ReadPotProjectorTest {
	@Test
	void producesCanonicalReadPotArtifactsWithoutWritingOrLosingExactValues() {
		PotId potId = PotId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
		ShareholderId shareholderId = ShareholderId.of(
				UUID.fromString("20000000-0000-0000-0000-000000000001"));
		ExpenseId expenseId = ExpenseId.of(UUID.fromString("30000000-0000-0000-0000-000000000001"));
		ProjectionKey key = new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(potId.value().toString()), 42);
		var input = new ReadPotProjectionInput(potId, 42, new Label("Trip"),
				List.of(new ReadPotProjectionInput.ShareholderInput(shareholderId, new Name("Alice"),
						Optional.empty(), Weight.of(Fraction.of(1, 3)))),
				List.of(new ReadPotProjectionInput.ExpenseInput(expenseId, new Label("Hotel"),
						Amount.of(Fraction.of(12345, 100)), LocalDate.parse("2026-09-19"), shareholderId,
						List.of(new ReadPotProjectionInput.ShareInput(shareholderId,
								Weight.of(Fraction.of(2, 7)))))));

		var projection = new ReadPotProjector().project(key, input);

		assertEquals(key, projection.projectionKey());
		assertEquals(3, projection.artifacts().size());
		JsonObject shareholder = payload(projection, ReadPotProjectionDefinition.SHAREHOLDER.value());
		assertSame(JsonNull.INSTANCE, shareholder.values().get("userId"));
		assertFraction((JsonObject) shareholder.values().get("part"), 1, 3);
		JsonObject expense = payload(projection, ReadPotProjectionDefinition.EXPENSE.value());
		assertEquals(new JsonString("2026-09-19"), expense.values().get("date"));
		assertFraction((JsonObject) expense.values().get("amount"), 2469, 20);
		JsonArray shares = (JsonArray) expense.values().get("shares");
		assertFraction((JsonObject) ((JsonObject) shares.values().getFirst()).values().get("part"), 2, 7);
	}

	private static JsonObject payload(com.kartaguez.pocoma.domain.projection.Projection projection, String type) {
		return (JsonObject) projection.artifacts().stream()
				.filter(artifact -> artifact.artifactType().value().equals(type))
				.findFirst().orElseThrow().payload();
	}

	private static void assertFraction(JsonObject value, long numerator, long denominator) {
		assertEquals(new JsonNumber(BigDecimal.valueOf(numerator)), value.values().get("numerator"));
		assertEquals(new JsonNumber(BigDecimal.valueOf(denominator)), value.values().get("denominator"));
	}
}
