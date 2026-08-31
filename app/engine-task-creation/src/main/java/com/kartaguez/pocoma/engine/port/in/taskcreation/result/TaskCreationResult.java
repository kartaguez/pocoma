package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;

/** Outcome of planning and, when accepted, durable task materialization. */
public sealed interface TaskCreationResult {
	UUID eventId();
	PipelineDefinition pipeline();

	record Materialized(UUID eventId, PipelineDefinition pipeline, TaskCreationOutcome outcome,
			List<PersistedTaskReference> tasks) implements TaskCreationResult {
		public Materialized {
			requireNonNull(eventId, "eventId must not be null");
			requireNonNull(pipeline, "pipeline must not be null");
			requireNonNull(outcome, "outcome must not be null");
			tasks = List.copyOf(requireNonNull(tasks, "tasks must not be null"));
		}

		public int taskCount() {
			return tasks.size();
		}
	}

	record Rejected(UUID eventId, PipelineDefinition pipeline, String rejectionCode)
			implements TaskCreationResult {
		public Rejected {
			requireNonNull(eventId, "eventId must not be null");
			requireNonNull(pipeline, "pipeline must not be null");
			requireNonNull(rejectionCode, "rejectionCode must not be null");
			if (rejectionCode.isBlank()) {
				throw new IllegalArgumentException("rejectionCode must not be blank");
			}
		}
	}

	static Materialized created(EventPipelineTaskCreation creation, List<PersistedTaskReference> tasks) {
		return materialized(creation, TaskCreationOutcome.CREATED, tasks);
	}

	static Materialized alreadyCreated(EventPipelineTaskCreation creation, List<PersistedTaskReference> tasks) {
		return materialized(creation, TaskCreationOutcome.ALREADY_CREATED, tasks);
	}

	private static Materialized materialized(EventPipelineTaskCreation creation, TaskCreationOutcome outcome,
			List<PersistedTaskReference> tasks) {
		requireNonNull(creation, "creation must not be null");
		return new Materialized(creation.recordedEvent().eventId(), creation.pipeline(), outcome, tasks);
	}
}
