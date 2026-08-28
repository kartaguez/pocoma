package com.kartaguez.pocoma.engine.port.in.taskexecution.usecase;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTaskPayload;
import com.kartaguez.pocoma.engine.port.in.taskexecution.input.ExecuteTaskInput;

public interface ExecuteTaskUseCase {

	void executeTask(ExecuteTaskInput<? extends PipelineTaskPayload> input);
}
