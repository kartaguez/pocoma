package com.kartaguez.pocoma.engine.port.in.taskexecution.handler;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.taskexecution.model.TaskExecutionReport;

public interface TaskExecutionHandler<T extends TaskPayload> {

	PipelineDefinition pipeline();

	String taskType();

	Class<T> payloadType();

	TaskExecutionReport execute(T task);
}
