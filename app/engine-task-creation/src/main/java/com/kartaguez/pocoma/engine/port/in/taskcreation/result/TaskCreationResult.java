package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;

public final class TaskCreationResult {
	private final UUID eventId;
	private final PipelineDefinition pipeline;
	private final TaskCreationOutcome outcome;
	private final List<PersistedTaskReference> tasks;
	private final int taskCount;

	public TaskCreationResult(UUID eventId, PipelineDefinition pipeline, TaskCreationOutcome outcome,
			List<PersistedTaskReference> tasks) {
		this.eventId = requireNonNull(eventId, "eventId must not be null");
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.outcome = requireNonNull(outcome, "outcome must not be null");
		this.tasks = List.copyOf(requireNonNull(tasks, "tasks must not be null"));
		this.taskCount = this.tasks.size();
	}

	@Deprecated(forRemoval = true)
	public TaskCreationResult(UUID eventId, PipelineDefinition pipeline, TaskCreationOutcome outcome, int taskCount) {
		this.eventId = requireNonNull(eventId, "eventId must not be null");
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		this.outcome = requireNonNull(outcome, "outcome must not be null");
		if (taskCount < 0) throw new IllegalArgumentException("taskCount must not be negative");
		this.tasks = List.of();
		this.taskCount = taskCount;
	}

	public UUID eventId() { return eventId; }
	public PipelineDefinition pipeline() { return pipeline; }
	public TaskCreationOutcome outcome() { return outcome; }
	public List<PersistedTaskReference> tasks() { return tasks; }
	public int taskCount() { return taskCount; }

	public static TaskCreationResult created(EventPipelineTaskCreation creation, List<PersistedTaskReference> tasks) {
		return result(creation, TaskCreationOutcome.CREATED, tasks);
	}
	public static TaskCreationResult alreadyCreated(EventPipelineTaskCreation creation, List<PersistedTaskReference> tasks) {
		return result(creation, TaskCreationOutcome.ALREADY_CREATED, tasks);
	}
	@Deprecated(forRemoval = true)
	public static TaskCreationResult created(EventPipelineTaskCreation creation, int count) {
		return legacy(creation, TaskCreationOutcome.CREATED, count);
	}
	@Deprecated(forRemoval = true)
	public static TaskCreationResult alreadyCreated(EventPipelineTaskCreation creation, int count) {
		return legacy(creation, TaskCreationOutcome.ALREADY_CREATED, count);
	}
	private static TaskCreationResult result(EventPipelineTaskCreation creation, TaskCreationOutcome outcome,
			List<PersistedTaskReference> tasks) {
		requireNonNull(creation, "creation must not be null");
		return new TaskCreationResult(creation.recordedEvent().eventId(), creation.pipeline(), outcome, tasks);
	}
	private static TaskCreationResult legacy(EventPipelineTaskCreation creation, TaskCreationOutcome outcome, int count) {
		requireNonNull(creation, "creation must not be null");
		return new TaskCreationResult(creation.recordedEvent().eventId(), creation.pipeline(), outcome, count);
	}
}
