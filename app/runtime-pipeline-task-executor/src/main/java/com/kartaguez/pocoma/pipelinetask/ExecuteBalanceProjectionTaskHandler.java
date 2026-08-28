package com.kartaguez.pocoma.pipelinetask;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;

public final class ExecuteBalanceProjectionTaskHandler implements TaskExecutionHandler<ComputeBalancesTask> {

	private final ComputePotBalancesUseCase computePotBalancesUseCase;

	public ExecuteBalanceProjectionTaskHandler(ComputePotBalancesUseCase computePotBalancesUseCase) {
		this.computePotBalancesUseCase = requireNonNull(
				computePotBalancesUseCase, "computePotBalancesUseCase must not be null");
	}

	@Override
	public PipelineDefinition pipeline() {
		return ComputeBalancesTask.PIPELINE;
	}

	@Override
	public String taskType() {
		return ComputeBalancesTask.TASK_TYPE;
	}

	@Override
	public Class<ComputeBalancesTask> payloadType() {
		return ComputeBalancesTask.class;
	}

	@Override
	public void execute(ComputeBalancesTask task) {
		requireNonNull(task, "task must not be null");
		computePotBalancesUseCase.computePotBalances(task.potId(), task.targetVersion());
	}
}
