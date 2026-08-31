package com.kartaguez.pocoma.locator.consumption.event;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionFailureClassifier;

public final class EventConsumptionFailureClassifier implements ConsumptionFailureClassifier {
	private final Clock clock;

	public EventConsumptionFailureClassifier(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ProcessingFailure classify(RuntimeException failure) {
		requireNonNull(failure, "failure must not be null");
		String category = failure instanceof TaskCreationRejectedException ? "TASK_CREATION_REJECTED"
				: failure instanceof MissingTaskCreationStrategyException ? "EVENT_CONFIGURATION"
				: "EVENT_EXECUTION_FAILURE";
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new ProcessingFailure(category, message, clock.instant());
	}
}
