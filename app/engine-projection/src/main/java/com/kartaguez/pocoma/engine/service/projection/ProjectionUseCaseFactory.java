package com.kartaguez.pocoma.engine.service.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
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
			PotShareholdersPort potShareholdersPort,
			PotBalancesCalculator potBalancesCalculator,
			TransactionRunner transactionRunner) {
		return new TransactionalComputePotBalancesUseCase(
				new ComputePotBalancesService(
						potBalancesPort,
						projectedExpensePort,
						potShareholdersPort,
						potBalancesCalculator),
				Objects.requireNonNull(transactionRunner, "transactionRunner must not be null"));
	}

	public static BuildProjectionTasksUseCase buildProjectionTasksUseCase(
			BusinessEventOutboxPort outboxPort,
			ProjectionTaskPort projectionTaskPort,
			ProjectionEventPublisherPort eventPublisherPort) {
		Objects.requireNonNull(outboxPort, "outboxPort must not be null");
		return new BuildProjectionTasksService(projectionTaskPort, eventPublisherPort);
	}

	public static ExecuteProjectionTasksUseCase executeProjectionTasksUseCase(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ProjectionEventPublisherPort eventPublisherPort) {
		Objects.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
		return new ExecuteProjectionTasksService(computePotBalancesUseCase);
	}
}
