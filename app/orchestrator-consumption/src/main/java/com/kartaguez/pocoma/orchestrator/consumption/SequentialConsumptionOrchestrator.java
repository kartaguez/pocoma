package com.kartaguez.pocoma.orchestrator.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

/** Sequential pull orchestration. It never owns a transaction or acquires work ahead of execution. */
public final class SequentialConsumptionOrchestrator implements ConsumptionOrchestrator {
	private final ConsumptionLocator locator;
	private final AcquireConsumptionUseCase acquire;
	private final ExecuteConsumptionUseCase execute;
	private final HandleConsumptionFailureUseCase handleFailure;

	public SequentialConsumptionOrchestrator(ConsumptionLocator locator, AcquireConsumptionUseCase acquire,
			ExecuteConsumptionUseCase execute, HandleConsumptionFailureUseCase handleFailure) {
		this.locator = requireNonNull(locator, "locator must not be null");
		this.acquire = requireNonNull(acquire, "acquire must not be null");
		this.execute = requireNonNull(execute, "execute must not be null");
		this.handleFailure = requireNonNull(handleFailure, "handleFailure must not be null");
	}

	@Override
	public ConsumptionOrchestrationResult run(ConsumptionOrchestrationInput input) {
		requireNonNull(input, "input must not be null");
		State state = new State();
		while (true) {
			if (state.executions >= input.budget().maxConsumptionsExecuted()) {
				return state.exhausted(ConsumptionBudgetLimit.EXECUTIONS);
			}
			if (state.candidates >= input.budget().maxCandidatesInspected()) {
				return state.exhausted(ConsumptionBudgetLimit.CANDIDATES);
			}
			ConsumptionSearch search;
			try {
				search = requireNonNull(locator.openSearch(), "locator returned a null search");
			}
			catch (RuntimeException failure) {
				return state.failed(failure);
			}
			SearchOutcome outcome = scan(search, input, state);
			if (outcome instanceof SearchOutcome.ReturnResult returned) return returned.result();
		}
	}

	private SearchOutcome scan(ConsumptionSearch search, ConsumptionOrchestrationInput input, State state) {
		while (true) {
			if (state.candidates >= input.budget().maxCandidatesInspected()) {
				return closeThenReturn(search, state.exhausted(ConsumptionBudgetLimit.CANDIDATES), state);
			}
			Optional<LocatedConsumption> next;
			try {
				next = requireNonNull(search.next(), "search returned null");
			}
			catch (RuntimeException failure) {
				return closeThenReturn(search, state.failed(failure), state);
			}
			if (next.isEmpty()) return closeThenReturn(search, state.idle(), state);

			LocatedConsumption located = next.orElseThrow();
			state.candidates++;
			AcquireResult acquired;
			try {
				acquired = acquire.acquire(new AcquireConsumptionInput(
						located.consumptionKey(), input.workerId(), input.claimLease()));
			}
			catch (RuntimeException failure) {
				return closeThenReturn(search, state.failed(failure), state);
			}
			if (acquired instanceof AcquireResult.Busy busy) {
				state.observe(busy.leaseUntil());
				continue;
			}
			if (acquired instanceof AcquireResult.NotReady notReady) {
				state.observe(notReady.nextClaimAt());
				continue;
			}
			if (acquired instanceof AcquireResult.AlreadyDone) continue;

			AcquireResult.Acquired claim = (AcquireResult.Acquired) acquired;
			RuntimeException closeFailure = closeFailure(search);
			state.executions++;
			RuntimeException executionFailure = handleExecution(located, claim);
			if (executionFailure != null) {
				if (closeFailure != null) executionFailure.addSuppressed(closeFailure);
				return new SearchOutcome.ReturnResult(state.failed(executionFailure));
			}
			if (closeFailure != null) {
				return new SearchOutcome.ReturnResult(state.failed(closeFailure));
			}
			return new SearchOutcome.RestartSearch();
		}
	}

	/** Returns only infrastructure failures from classification or failure handling. */
	private RuntimeException handleExecution(LocatedConsumption located, AcquireResult.Acquired acquired) {
		var claim = acquired.claim();
		try {
			execute.execute(new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), located.execution()));
			return null;
		}
		catch (LostClaimException ignored) {
			return null;
		}
		catch (RuntimeException executionFailure) {
			try {
				var processingFailure = requireNonNull(
						located.failureClassifier().classify(executionFailure), "classifier returned null");
				handleFailure.handle(new HandleConsumptionFailureInput(
						claim.slotId(), claim.claimId(), processingFailure));
				return null;
			}
			catch (RuntimeException infrastructureFailure) {
				return infrastructureFailure;
			}
		}
	}

	private SearchOutcome closeThenReturn(ConsumptionSearch search, ConsumptionOrchestrationResult result, State state) {
		RuntimeException failure = closeFailure(search);
		return new SearchOutcome.ReturnResult(failure == null ? result : state.failed(failure));
	}

	private RuntimeException closeFailure(ConsumptionSearch search) {
		try {
			search.close();
			return null;
		}
		catch (RuntimeException failure) {
			return failure;
		}
	}

	private sealed interface SearchOutcome {
		record RestartSearch() implements SearchOutcome {}
		record ReturnResult(ConsumptionOrchestrationResult result) implements SearchOutcome {
			public ReturnResult { requireNonNull(result, "result must not be null"); }
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

		ConsumptionOrchestrationResult failed(RuntimeException cause) {
			return new ConsumptionOrchestrationResult.RuntimeFailure(cause, next(), counters());
		}

		private Optional<Instant> next() { return Optional.ofNullable(nextEligibility); }
		private ConsumptionOrchestrationCounters counters() {
			return new ConsumptionOrchestrationCounters(candidates, executions);
		}
	}
}
