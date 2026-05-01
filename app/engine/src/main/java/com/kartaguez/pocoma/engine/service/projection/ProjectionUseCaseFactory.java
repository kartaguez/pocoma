package com.kartaguez.pocoma.engine.service.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.projection.task.BuildProjectionTasksService;
import com.kartaguez.pocoma.engine.service.projection.task.ExecuteProjectionTasksService;
import com.kartaguez.pocoma.engine.service.transaction.projection.TransactionalComputePotBalancesUseCase;

public final class ProjectionUseCaseFactory {

	private ProjectionUseCaseFactory() {
	}

	public static ComputePotBalancesUseCase computePotBalancesUseCase(
			PotBalancesPort potBalancesPort,
			ProjectedExpensePort projectedExpensePort,
			PotBalancesCalculator potBalancesCalculator,
			TransactionRunner transactionRunner) {
		return new TransactionalComputePotBalancesUseCase(
				new ComputePotBalancesService(
						potBalancesPort,
						projectedExpensePort,
						potBalancesCalculator),
				Objects.requireNonNull(transactionRunner, "transactionRunner must not be null"));
	}

	public static BuildProjectionTasksUseCase buildProjectionTasksUseCase(
			BusinessEventOutboxPort outboxPort,
			ProjectionTaskPort projectionTaskPort) {
		return new BuildProjectionTasksService(outboxPort, projectionTaskPort);
	}

	public static ExecuteProjectionTasksUseCase executeProjectionTasksUseCase(
			ProjectionTaskPort projectionTaskPort,
			ComputePotBalancesUseCase computePotBalancesUseCase) {
		return new ExecuteProjectionTasksService(projectionTaskPort, computePotBalancesUseCase);
	}
}
