package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskExecutionResult;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator.AcquiredCandidatePolicy;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSource;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Canonical projection orchestration. Preparation runs after the acquisition transaction has closed. */
public final class ProjectionTaskConsumptionOrchestrator implements ConsumptionOrchestrator {
	private final AcquireThenFinalizeConsumptionOrchestrator<ProjectionTaskCandidate> delegate;

	public ProjectionTaskConsumptionOrchestrator(FencedConsumptionCandidateSource<ProjectionTaskCandidate> source,
			AcquireConsumptionUseCase acquire, ProjectionTaskConsumptionService execute) {
		requireNonNull(source); requireNonNull(acquire); requireNonNull(execute);
		this.delegate = new AcquireThenFinalizeConsumptionOrchestrator<>(
				source,
				candidate -> ProjectionTaskKeys.consumptionKey(candidate.task().projectionKey()),
				acquire,
				(candidate, claim) -> fenced(execute.execute(candidate.task(), claim)),
				AcquiredCandidatePolicy.FETCH_FRESH_PAGE);
	}

	@Override
	public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		return delegate.run(input);
	}

	private static FencedMutationResult fenced(ProjectionTaskExecutionResult result) {
		return requireNonNull(result) == ProjectionTaskExecutionResult.LOST_CLAIM
				? FencedMutationResult.LOST_CLAIM : FencedMutationResult.APPLIED;
	}
}
