package com.kartaguez.pocoma.locator.consumption.event.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;

class EventConsumptionTechnicalFailureClassifierTest {

	@Test
	void separatesPreciseTechnicalCodeFromRetryCategory() {
		var classifier = new EventConsumptionTechnicalFailureClassifier(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

		var failure = classifier.classify(new IllegalStateException("database unavailable"));

		assertEquals(new ProcessingFailureCode("ILLEGAL_STATE_EXCEPTION"), failure.code());
		assertEquals(EventConsumptionFailureCategory.EVENT_EXECUTION_FAILURE.name(), failure.category());
	}

	@Test
	void lostClaimCanNeverBecomeAProcessingFailure() {
		var classifier = new EventConsumptionTechnicalFailureClassifier(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		var lostClaim = new LostClaimException(UUID.randomUUID(), ClaimId.generate());

		assertThrows(IllegalArgumentException.class, () -> classifier.classify(lostClaim));
	}
}
