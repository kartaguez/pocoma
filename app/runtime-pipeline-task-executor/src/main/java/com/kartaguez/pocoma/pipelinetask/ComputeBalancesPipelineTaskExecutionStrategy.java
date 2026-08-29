package com.kartaguez.pocoma.pipelinetask;

import java.util.Objects;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionStrategy;
import com.kartaguez.pocoma.pipelinetask.mapping.ComputeBalancesRecordedTaskMapper;

public final class ComputeBalancesPipelineTaskExecutionStrategy implements PipelineTaskExecutionStrategy {

	public static final PipelineDefinition DEFINITION = ComputeBalancesTask.PIPELINE;
	public static final String TASK_TYPE = ComputeBalancesTask.TASK_TYPE;

	private final ExecuteTaskUseCase executeTaskUseCase;
	private final ComputeBalancesRecordedTaskMapper mapper;

	public ComputeBalancesPipelineTaskExecutionStrategy(
			ExecuteTaskUseCase executeTaskUseCase,
			ComputeBalancesRecordedTaskMapper mapper) {
		this.executeTaskUseCase = Objects.requireNonNull(executeTaskUseCase, "executeTaskUseCase must not be null");
		this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
	}

	@Override
	public PipelineDefinition definition() {
		return DEFINITION;
	}

	@Override
	public boolean supports(String taskType) {
		return TASK_TYPE.equals(taskType);
	}

	@Override
	public void execute(LegacyPipelineTask task) {
		executeTaskUseCase.executeTask(mapper.mapLegacy(task));
	}
}
