package com.kartaguez.pocoma.locator.consumption.command.failure;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;

class CommandConsumptionFailurePolicyTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private final CommandConsumptionFailurePolicy policy = new CommandConsumptionFailurePolicy();

	@Test
	void retriesOnlyExplicitTransientFailures() {
		assertInstanceOf(FailureDecision.RetryAfter.class, policy.decide(context("DEADLOCK", "TRANSIENT")));
	}

	@Test
	void failsUnknownRuntimeConfigurationInputAndInvariantImmediately() {
		assertInstanceOf(FailureDecision.Fail.class,
				policy.decide(context("COMMAND_EXECUTION_FAILURE", "COMMAND_EXECUTION_FAILURE")));
		assertInstanceOf(FailureDecision.Fail.class,
				policy.decide(context("INVALID_COMMAND_PAYLOAD", "COMMAND_CONFIGURATION")));
		assertInstanceOf(FailureDecision.Fail.class,
				policy.decide(context("COMMAND_NOT_FOUND", "COMMAND_INPUT_NOT_FOUND")));
		assertInstanceOf(FailureDecision.Fail.class,
				policy.decide(context("TECHNICAL_INVARIANT_VIOLATION", "COMMAND_TECHNICAL_INVARIANT")));
	}

	private static FailureContext context(String code, String category) {
		return new FailureContext(
				new ProcessingFailure(new ProcessingFailureCode(code), category, "failure", NOW), 1, NOW);
	}
}
