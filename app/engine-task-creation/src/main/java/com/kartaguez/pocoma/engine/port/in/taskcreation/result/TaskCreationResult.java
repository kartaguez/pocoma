package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;

public record TaskCreationResult(
		UUID eventId,
		PipelineDefinition pipeline,
		TaskCreationOutcome outcome,
		int taskCount) {

	public TaskCreationResult {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(outcome, "outcome must not be null");
		if (taskCount < 0) {
			throw new IllegalArgumentException("taskCount must not be negative");
		}
	}

	public static TaskCreationResult created(EventPipelineTaskCreation creation, int taskCount) {
		return result(creation, TaskCreationOutcome.CREATED, taskCount);
	}

	public static TaskCreationResult alreadyCreated(EventPipelineTaskCreation creation, int taskCount) {
		return result(creation, TaskCreationOutcome.ALREADY_CREATED, taskCount);
	}

	private static TaskCreationResult result(
			EventPipelineTaskCreation creation, TaskCreationOutcome outcome, int taskCount) {
		requireNonNull(creation, "creation must not be null");
		return new TaskCreationResult(creation.recordedEvent().eventId(), creation.pipeline(), outcome, taskCount);
	}
}
