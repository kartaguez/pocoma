package com.kartaguez.pocoma.locator.consumption.task.failure;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;

class TaskConsumptionTechnicalFailureClassifierTest {

	@Test
	void lostClaimCanNeverBecomeAProcessingFailure() {
		var classifier = new TaskConsumptionTechnicalFailureClassifier(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		var lostClaim = new LostClaimException(UUID.randomUUID(), ClaimId.generate());

		assertThrows(IllegalArgumentException.class, () -> classifier.classify(lostClaim));
	}
}
