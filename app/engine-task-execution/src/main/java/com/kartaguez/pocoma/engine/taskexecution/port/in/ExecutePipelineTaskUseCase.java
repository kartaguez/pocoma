package com.kartaguez.pocoma.engine.taskexecution.port.in;

/** Transitional worker-facing bridge. New incoming adapters should use {@code ExecuteTaskUseCase}. */
public interface ExecutePipelineTaskUseCase {

	void executeTask(ExecutePipelineTaskCommand command);
}
