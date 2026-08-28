package com.kartaguez.pocoma.engine.taskexecution.model;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

/**
 * Transitional adapter contract for workers still carrying a durable {@link PipelineTask}.
 * New functional execution must use {@code TaskExecutionHandler}.
 */
public interface PipelineTaskExecutionStrategy {

	PipelineDefinition definition();

	boolean supports(String taskType);

	void execute(PipelineTask task);
}
