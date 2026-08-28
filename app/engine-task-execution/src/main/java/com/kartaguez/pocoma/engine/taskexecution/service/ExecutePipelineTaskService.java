package com.kartaguez.pocoma.engine.taskexecution.service;

import java.util.Objects;

import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionRegistry;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionStrategy;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskCommand;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskUseCase;

/** Transitional router retained until workers map durable tasks before entering the functional engine. */
public final class ExecutePipelineTaskService implements ExecutePipelineTaskUseCase {

	private final PipelineTaskExecutionRegistry registry;

	public ExecutePipelineTaskService(PipelineTaskExecutionRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry must not be null");
	}

	@Override
	public void executeTask(ExecutePipelineTaskCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		LegacyPipelineTask task = command.task();
		PipelineTaskExecutionStrategy strategy = registry.find(task)
				.orElseThrow(() -> new IllegalArgumentException(
						"No active pipeline task strategy registered for " + task.pipeline() + " / " + task.taskType()));
		strategy.execute(task);
	}
}
