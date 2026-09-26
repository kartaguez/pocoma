package com.kartaguez.pocoma.orchestrator.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSearch;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSource;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionFinalizer;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Generic pull orchestration for work whose durable effect is protected by fenced finalization. */
public final class AcquireThenFinalizeConsumptionOrchestrator<C> implements ConsumptionOrchestrator {
	private static final int MAX_PAGE_SIZE = 50;

	private final FencedConsumptionCandidateSource<C> source;
	private final Function<? super C, ConsumptionKey> keyMapper;
	private final AcquireConsumptionUseCase acquire;
	private final FencedConsumptionFinalizer<C> finalizer;

	public AcquireThenFinalizeConsumptionOrchestrator(
			FencedConsumptionCandidateSource<C> source,
			Function<? super C, ConsumptionKey> keyMapper,
			AcquireConsumptionUseCase acquire,
			FencedConsumptionFinalizer<C> finalizer) {
		this.source = requireNonNull(source, "source must not be null");
		this.keyMapper = requireNonNull(keyMapper, "keyMapper must not be null");
		this.acquire = requireNonNull(acquire, "acquire must not be null");
		this.finalizer = requireNonNull(finalizer, "finalizer must not be null");
	}

	@Override
	public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		requireNonNull(input, "input must not be null");
		State state = new State();
		try (FencedConsumptionCandidateSearch<C> search =
				requireNonNull(source.openSearch(), "source returned a null search")) {
			return scan(search, input, state);
		} catch (RuntimeException failure) {
			return state.failed(failure);
		}
	}

	private ConsumptionOrchestrationResult scan(
			FencedConsumptionCandidateSearch<C> search,
			ConsumptionOrchestrationInput input,
			State state) {
		while (true) {
			if (state.executions >= input.budget().maxConsumptionsExecuted()) {
				return state.exhausted(ConsumptionBudgetLimit.EXECUTIONS);
			}
			if (state.candidates >= input.budget().maxCandidatesInspected()) {
				return state.exhausted(ConsumptionBudgetLimit.CANDIDATES);
			}
			int limit = Math.min(MAX_PAGE_SIZE, input.budget().maxCandidatesInspected() - state.candidates);
			var page = ListSupport.copyAndRequireCandidates(search.nextPage(limit));
			if (page.isEmpty()) return state.idle();

			for (C candidate : page) {
				if (state.executions >= input.budget().maxConsumptionsExecuted()) {
					return state.exhausted(ConsumptionBudgetLimit.EXECUTIONS);
				}
				if (state.candidates >= input.budget().maxCandidatesInspected()) {
					return state.exhausted(ConsumptionBudgetLimit.CANDIDATES);
				}
				state.candidates++;
				ConsumptionKey key = requireNonNull(keyMapper.apply(candidate), "keyMapper returned null");
				AcquireResult result = requireNonNull(acquire.acquire(new AcquireConsumptionInput(
						key, input.workerId(), input.claimLease())), "acquire returned null");
				if (result instanceof AcquireResult.Busy busy) {
					state.observe(busy.leaseUntil());
					continue;
				}
				if (result instanceof AcquireResult.NotReady notReady) {
					state.observe(notReady.nextClaimAt());
					continue;
				}
				if (result instanceof AcquireResult.Acquired acquired) {
					state.executions++;
					requireNonNull(finalizer.finalize(candidate, acquired.claim()), "finalizer returned null");
				}
			}
		}
	}

	private static final class ListSupport {
		private static <C> java.util.List<C> copyAndRequireCandidates(java.util.List<C> candidates) {
			var copy = java.util.List.copyOf(requireNonNull(candidates, "search returned null"));
			if (copy.stream().anyMatch(java.util.Objects::isNull)) {
				throw new NullPointerException("search returned a null candidate");
			}
			return copy;
		}
	}

	private static final class State {
		private int candidates;
		private int executions;
		private Instant nextEligibility;

		void observe(Instant eligibility) {
			requireNonNull(eligibility, "eligibility must not be null");
			if (nextEligibility == null || eligibility.isBefore(nextEligibility)) nextEligibility = eligibility;
		}

		ConsumptionOrchestrationResult idle() {
			return new ConsumptionOrchestrationResult.Idle(next(), counters());
		}

		ConsumptionOrchestrationResult exhausted(ConsumptionBudgetLimit limit) {
			return new ConsumptionOrchestrationResult.BudgetExhausted(limit, next(), counters());
		}

		ConsumptionOrchestrationResult failed(RuntimeException failure) {
			return new ConsumptionOrchestrationResult.RuntimeFailure(failure, next(), counters());
		}

		private Optional<Instant> next() { return Optional.ofNullable(nextEligibility); }
		private ConsumptionOrchestrationCounters counters() {
			return new ConsumptionOrchestrationCounters(candidates, executions);
		}
	}
}
