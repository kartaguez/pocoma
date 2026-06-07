package com.kartaguez.pocoma.engine.taskexecution.model;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

public interface PipelineTaskExecutionStrategy {

	PipelineDefinition definition();

	boolean supports(String taskType);

	void execute(PipelineTask task);
}
