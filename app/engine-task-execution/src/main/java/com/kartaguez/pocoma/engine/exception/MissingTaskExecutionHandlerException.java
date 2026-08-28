package com.kartaguez.pocoma.engine.exception;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public final class MissingTaskExecutionHandlerException extends RuntimeException {

	public MissingTaskExecutionHandlerException(PipelineDefinition pipeline, String taskType) {
		super("No task execution handler registered for " + pipeline + " / " + taskType);
	}
}
