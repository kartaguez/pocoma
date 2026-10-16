package com.kartaguez.pocoma.runtime.consumption;

import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import java.util.Objects;

/** Rejects ambient callers before discovery, acquisition or classification. */
public record ConsumptionOwnerOrchestrator(ConsumptionOrchestrator delegate) implements ConsumptionOrchestrator {
	public ConsumptionOwnerOrchestrator { Objects.requireNonNull(delegate); }
	@Override public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		ConsumptionOwnerTransactionRunner.requireNoAmbientTransaction();
		return delegate.run(input);
	}
}
