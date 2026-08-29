package com.kartaguez.pocoma.supra.worker.task.mapping;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.task.TaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

/** Maps one durable task representation to the typed input owned by its pipeline. */
public interface RecordedTaskExecutionMapper<T extends TaskPayload> {
	PipelineDefinition pipeline();
	String taskType();
	Class<T> payloadType();
	ExecuteTaskInput<T> map(RecordedTask task);
}
