package com.kartaguez.pocoma.engine.port.in.taskexecution.handler;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.task.TaskPayload;

public interface TaskExecutionHandler<T extends TaskPayload> {

	PipelineDefinition pipeline();

	String taskType();

	Class<T> payloadType();

	void execute(T task);
}
