package com.kartaguez.pocoma.orchestrator.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.projection.task.ExecuteProjectionTaskService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Canonical projection orchestration. Preparation runs after the acquisition transaction has closed. */
public final class ProjectionTaskConsumptionOrchestrator implements ConsumptionOrchestrator {
	private final ProjectionType projectionType;
	private final int segmentIndex;
	private final int segmentCount;
	private final ProjectionTaskStorePort tasks;
	private final AcquireConsumptionUseCase acquire;
	private final ExecuteProjectionTaskService execute;

	public ProjectionTaskConsumptionOrchestrator(ProjectionType projectionType, int segmentIndex, int segmentCount,
			ProjectionTaskStorePort tasks, AcquireConsumptionUseCase acquire, ExecuteProjectionTaskService execute) {
		this.projectionType = requireNonNull(projectionType); this.tasks = requireNonNull(tasks);
		this.acquire = requireNonNull(acquire); this.execute = requireNonNull(execute);
		if (segmentCount < 1 || segmentIndex < 0 || segmentIndex >= segmentCount) throw new IllegalArgumentException("invalid segment");
		this.segmentIndex = segmentIndex; this.segmentCount = segmentCount;
	}

	@Override public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		requireNonNull(input, "input must not be null");
		int candidates = 0, executions = 0;
		Optional<Instant> nextEligibility = Optional.empty();
		Optional<Instant> afterTime = Optional.empty();
		Optional<UUID> afterId = Optional.empty();
		try {
			while (candidates < input.budget().maxCandidatesInspected()
					&& executions < input.budget().maxConsumptionsExecuted()) {
				var page = tasks.findCandidates(projectionType, segmentIndex, segmentCount, afterTime, afterId,
						Math.min(50, input.budget().maxCandidatesInspected() - candidates));
				if (page.isEmpty()) return new ConsumptionOrchestrationResult.Idle(nextEligibility,
						new ConsumptionOrchestrationCounters(candidates, executions));
				for (ProjectionTaskCandidate candidate : page) {
					candidates++;
					afterTime = Optional.of(candidate.createdAt()); afterId = Optional.of(candidate.rowId());
					var acquired = acquire.acquire(new AcquireConsumptionInput(
							ProjectionTaskKeys.consumptionKey(candidate.task().projectionKey()), input.workerId(),
							input.claimLease(), ConsumptionAcquisitionPrecondition.alwaysSatisfied()));
					if (acquired instanceof AcquireResult.Busy busy) {
						nextEligibility = earlier(nextEligibility, busy.leaseUntil()); continue;
					}
					if (acquired instanceof AcquireResult.NotReady notReady) {
						nextEligibility = earlier(nextEligibility, notReady.nextClaimAt()); continue;
					}
					if (acquired instanceof AcquireResult.Acquired winner) {
						executions++;
						execute.execute(candidate.task(), winner.claim());
						break;
					}
				}
			}
			var limit = executions >= input.budget().maxConsumptionsExecuted()
					? ConsumptionBudgetLimit.EXECUTIONS : ConsumptionBudgetLimit.CANDIDATES;
			return new ConsumptionOrchestrationResult.BudgetExhausted(limit, nextEligibility,
					new ConsumptionOrchestrationCounters(candidates, executions));
		} catch (RuntimeException failure) {
			return new ConsumptionOrchestrationResult.RuntimeFailure(failure, nextEligibility,
					new ConsumptionOrchestrationCounters(candidates, executions));
		}
	}

	private static Optional<Instant> earlier(Optional<Instant> current, Instant candidate) {
		return current.isEmpty() || candidate.isBefore(current.orElseThrow()) ? Optional.of(candidate) : current;
	}
}
