package com.kartaguez.pocoma.locator.consumption.task.failure;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Locale;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.InvalidTaskPayloadTypeException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
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
		ProcessingFailureCode code;
		if (failure instanceof NonRetryableTaskTechnicalFailure terminal) {
			category = terminal.failureCategory();
			code = new ProcessingFailureCode(terminal.failureCode());
		}
		else if (failure instanceof MissingTaskExecutionHandlerException
				|| failure instanceof InvalidTaskPayloadTypeException) {
			category = "TASK_CONFIGURATION";
			code = new ProcessingFailureCode(failure instanceof InvalidTaskPayloadTypeException
					? "INVALID_TASK_PAYLOAD" : "MISSING_TASK_EXECUTION_HANDLER");
		}
		else {
			category = TaskConsumptionFailureCategory.TASK_EXECUTION_FAILURE.name();
			code = new ProcessingFailureCode(exceptionCode(failure));
		}
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new ProcessingFailure(code, category, message, clock.instant());
	}

	private static String exceptionCode(RuntimeException failure) {
		String simpleName = failure.getClass().getSimpleName();
		if (simpleName.isBlank()) return TaskConsumptionFailureCategory.TASK_EXECUTION_FAILURE.name();
		return simpleName.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
				.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}
}
