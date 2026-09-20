package com.kartaguez.pocoma.engine.projection.balance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.TemporaryProjectionPreparationException;

class PotBalancesProjectorTest {
	@Test
	void producesTheSingleCanonicalPotBalancesProjectionAndPreservesSignedFractions() {
		PotId potId = PotId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
		ShareholderId shareholderId = ShareholderId.of(
				UUID.fromString("20000000-0000-0000-0000-000000000001"));
		ProjectionKey key = new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(potId.value().toString()), 42);
		var balances = new PotBalances(potId, 42,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.of(-2, 7))));

		var projection = new PotBalancesProjector().project(key, balances);

		assertEquals("POT_BALANCES", projection.projectionType().value());
		assertEquals(1, projection.artifacts().size());
		var payload = (JsonObject) projection.artifacts().getFirst().payload();
		var fraction = (JsonObject) payload.values().get("balance");
		assertEquals(new JsonNumber(BigDecimal.valueOf(-2)), fraction.values().get("numerator"));
		assertEquals(new JsonNumber(BigDecimal.valueOf(7)), fraction.values().get("denominator"));
	}

	@Test
	void anExactDependencyThatIsNotReadyRemainsATemporaryPreparationFailure() {
		ProcessingFailure failure = new ProcessingFailure(new ProcessingFailureCode("EXACT_DEPENDENCY_NOT_READY"),
				"projection", "READ_POT@42 is not available", Instant.parse("2026-09-20T10:00:00Z"));
		CalculatePotBalancesAtVersionUseCase unavailable = (potId, version) -> {
			throw new TemporaryProjectionPreparationException(failure, null);
		};
		ProjectionKey key = new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId("10000000-0000-0000-0000-000000000001"), 42);
		var preparation = new PreparePotBalancesProjection(unavailable, new PotBalancesProjector(),
				new ProjectionValidator((schema, payload) -> true));

		var result = assertInstanceOf(ProjectionPreparationOutcome.Temporary.class, preparation.prepare(key));

		assertEquals(failure, result.failure());
	}
}
