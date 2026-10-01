package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionFinalizer;

/** Ensures one ProjectionTask only inside the generic fenced finalization transaction. */
public final class ProjectionMaterializationConsumptionService
		implements FencedConsumptionFinalizer<ProjectionMaterializationCandidate> {
	private final FinalizeConsumptionUseCase finalizer;
	private final ProjectionTaskStorePort tasks;

	public ProjectionMaterializationConsumptionService(
			FinalizeConsumptionUseCase finalizer, ProjectionTaskStorePort tasks) {
		this.finalizer = requireNonNull(finalizer, "finalizer must not be null");
		this.tasks = requireNonNull(tasks, "tasks must not be null");
	}

	@Override
	public FencedMutationResult finalize(ProjectionMaterializationCandidate candidate, Claim claim) {
		requireNonNull(candidate, "candidate must not be null");
		requireNonNull(claim, "claim must not be null");
		ProjectionKey key = new ProjectionKey(
				candidate.projectionType(), candidate.targetObjectType(),
				candidate.targetObjectId(), candidate.targetVersion());
		return finalizer.finalizeConsumption(new FinalizeConsumptionInput(
				claim.slotId(), claim.claimId(), new Success(),
				() -> tasks.ensure(key, candidate.recordedAt())));
	}
}
