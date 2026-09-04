package com.kartaguez.pocoma.locator.consumption.event.failure;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Locale;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.MissingTaskCreationStrategyException;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;

/** Classifies only failures that escaped a valid Event execution. */
public final class EventConsumptionTechnicalFailureClassifier implements ConsumptionTechnicalFailureClassifier {
	private final Clock clock;

	public EventConsumptionTechnicalFailureClassifier(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ProcessingFailure classify(RuntimeException failure) {
		requireNonNull(failure, "failure must not be null");
		if (failure instanceof LostClaimException) {
			throw new IllegalArgumentException("LostClaimException must never be classified", failure);
		}
		if (failure instanceof TaskCreationRejectedException) {
			throw new IllegalArgumentException("A business rejection escaped the task-creation boundary", failure);
		}
		EventConsumptionFailureCategory category = failure instanceof MissingTaskCreationStrategyException
				? EventConsumptionFailureCategory.EVENT_CONFIGURATION
				: failure instanceof RecordedEventNotFoundException
						? EventConsumptionFailureCategory.EVENT_INPUT_NOT_FOUND
						: EventConsumptionFailureCategory.EVENT_EXECUTION_FAILURE;
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new ProcessingFailure(codeFor(failure), category.name(), message, clock.instant());
	}

	private static ProcessingFailureCode codeFor(RuntimeException failure) {
		if (failure instanceof MissingTaskCreationStrategyException) {
			return new ProcessingFailureCode("MISSING_TASK_CREATION_STRATEGY");
		}
		if (failure instanceof RecordedEventNotFoundException) {
			return new ProcessingFailureCode("RECORDED_EVENT_NOT_FOUND");
		}
		return new ProcessingFailureCode(exceptionCode(failure));
	}

	private static String exceptionCode(RuntimeException failure) {
		String simpleName = failure.getClass().getSimpleName();
		if (simpleName.isBlank()) return EventConsumptionFailureCategory.EVENT_EXECUTION_FAILURE.name();
		return simpleName.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
				.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}
}
