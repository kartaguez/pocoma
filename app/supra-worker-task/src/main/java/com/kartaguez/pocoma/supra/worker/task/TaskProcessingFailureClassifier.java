package com.kartaguez.pocoma.supra.worker.task;

import static java.util.Objects.requireNonNull;
import static com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException.INVALID_TASK_PAYLOAD;

import java.time.Clock;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.exception.TaskExecutionRejectedException;
import com.kartaguez.pocoma.supra.worker.task.mapping.RecordedTaskMappingException;

@FunctionalInterface
public interface TaskProcessingFailureClassifier {
	Optional<ProcessingFailure> classify(Throwable failure);

	static TaskProcessingFailureClassifier conservative(Clock clock) {
		requireNonNull(clock, "clock must not be null");
		return failure -> {
			requireNonNull(failure, "failure must not be null");
			if (failure instanceof RecordedTaskMappingException mapping
					&& INVALID_TASK_PAYLOAD.equals(mapping.code())) {
				return Optional.of(new ProcessingFailure(
						INVALID_TASK_PAYLOAD, mapping.getMessage(), clock.instant()));
			}
			if (failure instanceof TaskExecutionRejectedException rejected) {
				return Optional.of(new ProcessingFailure(
						"TASK_EXECUTION_REJECTED", rejected.getMessage(), clock.instant()));
			}
			return Optional.empty();
		};
	}
}
