package com.kartaguez.pocoma.locator.consumption.task.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;

class TaskConsumptionFailurePolicyTest {
	private final TaskConsumptionFailurePolicy policy = new TaskConsumptionFailurePolicy();

	@Test
	void projectionConflictFailsImmediately() {
		assertInstanceOf(FailureDecision.Fail.class, policy.decide(context("BALANCE_PROJECTION_CONFLICT", 1)));
	}

	@Test
	void genericTechnicalFailuresUseOneFiveThirtySecondBackoffThenFail() {
		assertEquals(new FailureDecision.RetryAfter(java.time.Duration.ofSeconds(1)),
				policy.decide(context("TASK_EXECUTION_FAILURE", 1)));
		assertEquals(new FailureDecision.RetryAfter(java.time.Duration.ofSeconds(5)),
				policy.decide(context("TASK_EXECUTION_FAILURE", 2)));
		assertEquals(new FailureDecision.RetryAfter(java.time.Duration.ofSeconds(30)),
				policy.decide(context("TASK_EXECUTION_FAILURE", 3)));
		assertInstanceOf(FailureDecision.Fail.class, policy.decide(context("TASK_EXECUTION_FAILURE", 4)));
	}

	private static FailureContext context(String category, int attempt) {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		return new FailureContext(new ProcessingFailure(
				new ProcessingFailureCode("TEST_FAILURE"), category, "failure", now), attempt, now);
	}
}
