package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;

public final class MissingTaskCreationStrategyException extends RuntimeException {

	private final PipelineDefinition pipeline;

	public MissingTaskCreationStrategyException(PipelineDefinition pipeline) {
		super("No task-creation strategy registered for " + requireNonNull(pipeline, "pipeline must not be null"));
		this.pipeline = pipeline;
	}

	public PipelineDefinition pipeline() {
		return pipeline;
	}
}
