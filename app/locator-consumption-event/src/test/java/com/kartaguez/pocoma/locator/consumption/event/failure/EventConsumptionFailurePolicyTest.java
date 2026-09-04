package com.kartaguez.pocoma.locator.consumption.event.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;

class EventConsumptionFailurePolicyTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
	private final EventConsumptionTechnicalFailureClassifier classifier =
			new EventConsumptionTechnicalFailureClassifier(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void classifiesMissingInputAsTerminalAndExecutionFailureAsRetryable() {
		ProcessingFailure missing = classifier.classify(new RecordedEventNotFoundException(UUID.randomUUID()));
		ProcessingFailure technical = classifier.classify(new IllegalStateException("database unavailable"));

		assertEquals(EventConsumptionFailureCategory.EVENT_INPUT_NOT_FOUND.name(), missing.category());
		assertEquals(new ProcessingFailureCode("RECORDED_EVENT_NOT_FOUND"), missing.code());
		assertInstanceOf(FailureDecision.Fail.class,
				new EventConsumptionFailurePolicy().decide(new FailureContext(missing, 1, NOW)));
		assertEquals(EventConsumptionFailureCategory.EVENT_EXECUTION_FAILURE.name(), technical.category());
		assertEquals(new ProcessingFailureCode("ILLEGAL_STATE_EXCEPTION"), technical.code());
		var retry = assertInstanceOf(FailureDecision.RetryAfter.class,
				new EventConsumptionFailurePolicy().decide(new FailureContext(technical, 1, NOW)));
		assertEquals(java.time.Duration.ofSeconds(1), retry.duration());
	}

	@Test
	void businessRejectionCannotBeClassifiedAsAProcessingFailure() {
		assertThrows(IllegalArgumentException.class,
				() -> classifier.classify(new TaskCreationRejectedException("NOT_APPLICABLE", "not applicable")));
	}
}
