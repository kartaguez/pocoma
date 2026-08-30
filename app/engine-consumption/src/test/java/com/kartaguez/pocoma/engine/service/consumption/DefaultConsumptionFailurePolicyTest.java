package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;

class DefaultConsumptionFailurePolicyTest {

	private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
	private final DefaultConsumptionFailurePolicy policy = new DefaultConsumptionFailurePolicy();

	@Test
	void appliesTheV1BackoffAndStopsAtTheFourthAttempt() {
		assertEquals(Duration.ofSeconds(1), retry(1).duration());
		assertEquals(Duration.ofSeconds(5), retry(2).duration());
		assertEquals(Duration.ofSeconds(30), retry(3).duration());
		assertInstanceOf(Fail.class, policy.decide(context(4)));
		assertInstanceOf(Fail.class, policy.decide(context(10)));
	}

	private RetryAfter retry(int attempt) {
		return assertInstanceOf(RetryAfter.class, policy.decide(context(attempt)));
	}

	private FailureContext context(int attempt) {
		return new FailureContext(new ProcessingFailure("temporary", "unavailable", NOW), attempt, NOW);
	}
}
