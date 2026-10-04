package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProduceProjectionTaskService;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionFinalizer;

/** Ensures one ProjectionTask only inside the generic fenced finalization transaction. */
public final class ProjectionMaterializationConsumptionService
		implements FencedConsumptionFinalizer<ProjectionMaterializationCandidate> {
	private final FinalizeConsumptionUseCase finalizer;
	private final ProduceProjectionTaskService producer;

	public ProjectionMaterializationConsumptionService(
			FinalizeConsumptionUseCase finalizer, ProduceProjectionTaskService producer) {
		this.finalizer = requireNonNull(finalizer, "finalizer must not be null");
		this.producer = requireNonNull(producer, "producer must not be null");
	}

	@Override
	public FencedMutationResult finalize(ProjectionMaterializationCandidate candidate, Claim claim) {
		requireNonNull(candidate, "candidate must not be null");
		requireNonNull(claim, "claim must not be null");
		return finalizer.finalizeConsumption(new FinalizeConsumptionInput(
				claim.slotId(), claim.claimId(), new Success(),
				() -> producer.ensure(candidate)));
	}
}
