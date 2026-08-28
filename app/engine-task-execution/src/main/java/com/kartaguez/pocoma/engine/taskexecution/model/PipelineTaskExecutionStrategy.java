package com.kartaguez.pocoma.engine.taskexecution.model;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;

/**
 * Transitional adapter contract for workers still carrying a durable {@link LegacyPipelineTask}.
 * New functional execution must use {@code TaskExecutionHandler}.
 */
public interface PipelineTaskExecutionStrategy {

	PipelineDefinition definition();

	boolean supports(String taskType);

	void execute(LegacyPipelineTask task);
}
