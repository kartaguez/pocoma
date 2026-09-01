package com.kartaguez.pocoma.engine.port.in.taskexecution.mapper;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

public interface RecordedTaskExecutionMapper<T extends TaskPayload> {
	PipelineDefinition pipeline();
	String taskType();
	ExecuteTaskInput<T> map(RecordedTask task);
}
