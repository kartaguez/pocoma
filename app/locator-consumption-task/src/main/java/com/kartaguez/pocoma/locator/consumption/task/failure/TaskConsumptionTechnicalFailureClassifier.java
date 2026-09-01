package com.kartaguez.pocoma.locator.consumption.task.failure;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.exception.InvalidTaskPayloadTypeException;
import com.kartaguez.pocoma.engine.exception.LostClaimException;
import com.kartaguez.pocoma.engine.exception.MissingTaskExecutionHandlerException;
import com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;

public final class TaskConsumptionTechnicalFailureClassifier implements ConsumptionTechnicalFailureClassifier {
	private final Clock clock;
	public TaskConsumptionTechnicalFailureClassifier(Clock clock) { this.clock = requireNonNull(clock); }

	@Override public ProcessingFailure classify(RuntimeException failure) {
		requireNonNull(failure, "failure must not be null");
		if (failure instanceof LostClaimException) {
			throw new IllegalArgumentException("LostClaimException must never be classified", failure);
		}
		String category;
		if (failure instanceof NonRetryableTaskTechnicalFailure terminal) category = terminal.failureCategory();
		else if (failure instanceof MissingTaskExecutionHandlerException
				|| failure instanceof InvalidTaskPayloadTypeException) category = "TASK_CONFIGURATION";
		else category = TaskConsumptionFailureCategory.TASK_EXECUTION_FAILURE.name();
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new ProcessingFailure(category, message, clock.instant());
	}
}
