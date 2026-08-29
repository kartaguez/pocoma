package com.kartaguez.pocoma.supra.worker.event;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.exception.TaskCreationRejectedException;

@FunctionalInterface
public interface EventProcessingFailureClassifier {

	Optional<ProcessingFailure> classify(Throwable failure);

	static EventProcessingFailureClassifier conservative(Clock clock) {
		requireNonNull(clock, "clock must not be null");
		return failure -> {
			requireNonNull(failure, "failure must not be null");
			if (failure instanceof TaskCreationRejectedException rejected) {
				return Optional.of(new ProcessingFailure(
						"TASK_CREATION_REJECTED", rejected.getMessage(), clock.instant()));
			}
			return Optional.empty();
		};
	}
}
