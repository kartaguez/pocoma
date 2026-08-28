package com.kartaguez.pocoma.engine.port.in.taskexecution.handler;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineTaskPayload;

public interface TaskExecutionHandler<T extends PipelineTaskPayload> {

	PipelineDefinition pipeline();

	String taskType();

	Class<T> payloadType();

	void execute(T task);
}
