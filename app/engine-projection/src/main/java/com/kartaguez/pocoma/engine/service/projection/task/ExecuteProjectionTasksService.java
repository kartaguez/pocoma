package com.kartaguez.pocoma.engine.service.projection.task;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;

public final class ExecuteProjectionTasksService implements ExecuteProjectionTasksUseCase {

	private final ComputePotBalancesUseCase computePotBalancesUseCase;

	public ExecuteProjectionTasksService(
			ComputePotBalancesUseCase computePotBalancesUseCase) {
		this.computePotBalancesUseCase = Objects.requireNonNull(
				computePotBalancesUseCase,
				"computePotBalancesUseCase must not be null");
	}

	@Override
	public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		computePotBalancesUseCase.computePotBalances(command.potId(), command.targetVersion());
	}
}
