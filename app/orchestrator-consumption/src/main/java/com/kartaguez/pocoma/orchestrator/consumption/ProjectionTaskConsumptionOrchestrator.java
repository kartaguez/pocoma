package com.kartaguez.pocoma.orchestrator.consumption;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskExecutionResult;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator.AcquiredCandidatePolicy;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSearch;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Canonical projection orchestration. Preparation runs after the acquisition transaction has closed. */
public final class ProjectionTaskConsumptionOrchestrator implements ConsumptionOrchestrator {
	private final AcquireThenFinalizeConsumptionOrchestrator<ProjectionTaskCandidate> delegate;

	public ProjectionTaskConsumptionOrchestrator(Set<ProjectionType> projectionTypes, int segmentIndex, int segmentCount,
			ProjectionTaskStorePort tasks, AcquireConsumptionUseCase acquire, ProjectionTaskConsumptionService execute) {
		Set<ProjectionType> types = Set.copyOf(requireNonNull(projectionTypes));
		if (types.isEmpty()) throw new IllegalArgumentException("projectionTypes must not be empty");
		requireNonNull(tasks); requireNonNull(acquire); requireNonNull(execute);
		if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount) throw new IllegalArgumentException("invalid segment");
		this.delegate = new AcquireThenFinalizeConsumptionOrchestrator<>(
				() -> search(types, segmentIndex, segmentCount, tasks),
				candidate -> ProjectionTaskKeys.consumptionKey(candidate.task().projectionKey()),
				acquire,
				(candidate, claim) -> fenced(execute.execute(candidate.task(), claim)),
				AcquiredCandidatePolicy.FETCH_FRESH_PAGE);
	}

	@Override
	public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		return delegate.run(input);
	}

	private static FencedConsumptionCandidateSearch<ProjectionTaskCandidate> search(
			Set<ProjectionType> projectionTypes, int segmentIndex, int segmentCount, ProjectionTaskStorePort tasks) {
		return new FencedConsumptionCandidateSearch<>() {
			private Optional<java.time.Instant> afterTime = Optional.empty();
			private Optional<UUID> afterId = Optional.empty();

			@Override
			public java.util.List<ProjectionTaskCandidate> nextPage(int limit) {
				var page = tasks.findCandidates(
						projectionTypes, segmentIndex, segmentCount, afterTime, afterId, limit);
				return page;
			}

			@Override
			public void candidateInspected(ProjectionTaskCandidate candidate) {
				afterTime = Optional.of(candidate.createdAt());
				afterId = Optional.of(candidate.rowId());
			}
		};
	}

	private static FencedMutationResult fenced(ProjectionTaskExecutionResult result) {
		return requireNonNull(result) == ProjectionTaskExecutionResult.LOST_CLAIM
				? FencedMutationResult.LOST_CLAIM : FencedMutationResult.APPLIED;
	}
}
