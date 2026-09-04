package com.kartaguez.pocoma.locator.consumption.task.failure;

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
import com.kartaguez.pocoma.locator.consumption.task.TaskRecordedInputNotFoundException;

class TaskConsumptionTechnicalFailureClassifierTest {

	@Test
	void separatesPreciseTechnicalCodeFromFailurePolicyCategory() {
		var classifier = new TaskConsumptionTechnicalFailureClassifier(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

		var missing = classifier.classify(new TaskRecordedInputNotFoundException(UUID.randomUUID()));
		var execution = classifier.classify(new IllegalStateException("database unavailable"));

		assertEquals(new ProcessingFailureCode("RECORDED_TASK_NOT_FOUND"), missing.code());
		assertEquals(TaskConsumptionFailureCategory.TASK_INPUT_NOT_FOUND.name(), missing.category());
		assertEquals(new ProcessingFailureCode("ILLEGAL_STATE_EXCEPTION"), execution.code());
		assertEquals(TaskConsumptionFailureCategory.TASK_EXECUTION_FAILURE.name(), execution.category());
	}

	@Test
	void lostClaimCanNeverBecomeAProcessingFailure() {
		var classifier = new TaskConsumptionTechnicalFailureClassifier(
				Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
		var lostClaim = new LostClaimException(UUID.randomUUID(), ClaimId.generate());

		assertThrows(IllegalArgumentException.class, () -> classifier.classify(lostClaim));
	}
}
