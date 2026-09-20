package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionFailureId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Prepared;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Temporary;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome.Terminal;
import com.kartaguez.pocoma.engine.projection.task.engine.ExecuteProjectionTaskUseCase;

public final class ProjectionTaskConsumptionService {
	private final ExecuteProjectionTaskUseCase projectionEngine;
	private final ProjectionWritePort projections;
	private final FinalizeConsumptionUseCase finalizer;
	private final HandleConsumptionFailureUseCase retryHandler;
	private final Clock clock;

	public ProjectionTaskConsumptionService(ExecuteProjectionTaskUseCase projectionEngine,
			ProjectionWritePort projections, FinalizeConsumptionUseCase finalizer,
			HandleConsumptionFailureUseCase retryHandler, Clock clock) {
		this.projectionEngine = requireNonNull(projectionEngine);
		this.projections = requireNonNull(projections);
		this.finalizer = requireNonNull(finalizer);
		this.retryHandler = requireNonNull(retryHandler);
		this.clock = requireNonNull(clock);
	}

	public ProjectionTaskExecutionResult execute(ProjectionTask task, Claim claim) {
		requireNonNull(task, "task must not be null");
		requireNonNull(claim, "claim must not be null");
		var key = task.projectionKey();
		var outcome = requireNonNull(projectionEngine.execute(task), "preparation outcome must not be null");
		FencedMutationResult result;
		if (outcome instanceof Prepared prepared) {
			result = finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(),
					new Success(), () -> projections.publish(prepared.projection())));
		} else if (outcome instanceof Temporary temporary) {
			result = retryHandler.handle(new HandleConsumptionFailureInput(
					claim.slotId(), claim.claimId(), temporary.failure()));
			return result == FencedMutationResult.APPLIED ? ProjectionTaskExecutionResult.RETRY_SCHEDULED
					: ProjectionTaskExecutionResult.LOST_CLAIM;
		} else {
			var terminal = (Terminal) outcome;
			var failure = new ProjectionFailure(ProjectionFailureId.random(), key, clock.instant());
			result = finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(),
					new TerminalFailure(terminal.failure()), () -> projections.recordFailure(failure)));
		}
		return result == FencedMutationResult.APPLIED ? ProjectionTaskExecutionResult.FINALIZED
				: ProjectionTaskExecutionResult.LOST_CLAIM;
	}
}
