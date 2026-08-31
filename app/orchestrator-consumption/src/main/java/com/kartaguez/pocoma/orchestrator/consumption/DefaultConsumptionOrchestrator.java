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

/** Sequential pull orchestration. It never owns a transaction or acquires work ahead of execution. */
public final class DefaultConsumptionOrchestrator implements ConsumptionOrchestrator {

	private final ConsumptionLocator locator;
	private final AcquireConsumptionUseCase acquire;
	private final ExecuteConsumptionUseCase execute;
	private final HandleConsumptionFailureUseCase handleFailure;

	public DefaultConsumptionOrchestrator(
			ConsumptionLocator locator,
			AcquireConsumptionUseCase acquire,
			ExecuteConsumptionUseCase execute,
			HandleConsumptionFailureUseCase handleFailure) {
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
				return state.exhausted(BudgetLimit.EXECUTIONS);
			}
			if (state.candidates >= input.budget().maxCandidatesInspected()) {
				return state.exhausted(BudgetLimit.CANDIDATES);
			}
			ConsumptionSearch search;
			try {
				search = requireNonNull(locator.openSearch(), "locator returned a null search");
			}
			catch (RuntimeException failure) {
				return state.failed(failure);
			}
			ConsumptionOrchestrationResult searchResult = scan(search, input, state);
			if (searchResult != null) {
				return searchResult;
			}
		}
	}

	/** Returns null only after an acquired candidate was handled and a new search is required. */
	private ConsumptionOrchestrationResult scan(
			ConsumptionSearch search, ConsumptionOrchestrationInput input, State state) {
		try {
			while (true) {
				if (state.candidates >= input.budget().maxCandidatesInspected()) {
					return closeThen(search, state.exhausted(BudgetLimit.CANDIDATES), state);
				}
				Optional<LocatedConsumption> next;
				try {
					next = requireNonNull(search.next(), "search returned null");
				}
				catch (RuntimeException failure) {
					return closeThen(search, state.failed(failure), state);
				}
				if (next.isEmpty()) {
					return closeThen(search, state.idle(), state);
				}
				LocatedConsumption located = next.orElseThrow();
				state.candidates++;
				AcquireResult result;
				try {
					result = acquire.acquire(new AcquireConsumptionInput(
							located.consumptionKey(), input.workerId(), input.claimLease()));
				}
				catch (RuntimeException failure) {
					return closeThen(search, state.failed(failure), state);
				}
				if (result instanceof AcquireResult.Busy busy) {
					state.observe(busy.leaseUntil());
					continue;
				}
				if (result instanceof AcquireResult.NotReady notReady) {
					state.observe(notReady.nextClaimAt());
					continue;
				}
				if (result instanceof AcquireResult.AlreadyDone) {
					continue;
				}
				AcquireResult.Acquired acquired = (AcquireResult.Acquired) result;
				ConsumptionOrchestrationResult closeFailure = closeFailure(search, state);
				if (closeFailure != null) {
					return closeFailure;
				}
				state.executions++;
				handleExecution(located, acquired, state);
				return null;
			}
		}
		catch (RuntimeFailureSignal signal) {
			return state.failed(signal.failure);
		}
	}

	private void handleExecution(LocatedConsumption located, AcquireResult.Acquired acquired, State state) {
		var claim = acquired.claim();
		try {
			execute.execute(new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), located.execution()));
		}
		catch (LostClaimException ignored) {
			// The transaction was rolled back. A fresh search observes the winner.
		}
		catch (RuntimeException executionFailure) {
			try {
				var processingFailure = requireNonNull(
						located.failureClassifier().classify(executionFailure), "classifier returned null");
				handleFailure.handle(new HandleConsumptionFailureInput(
						claim.slotId(), claim.claimId(), processingFailure));
			}
			catch (RuntimeException infrastructureFailure) {
				throw new RuntimeFailureSignal(infrastructureFailure);
			}
		}
	}

	private ConsumptionOrchestrationResult closeThen(
			ConsumptionSearch search, ConsumptionOrchestrationResult result, State state) {
		ConsumptionOrchestrationResult closeFailure = closeFailure(search, state);
		return closeFailure == null ? result : closeFailure;
	}

	private ConsumptionOrchestrationResult closeFailure(ConsumptionSearch search, State state) {
		try {
			search.close();
			return null;
		}
		catch (RuntimeException failure) {
			return state.failed(failure);
		}
	}

	private static final class State {
		private int candidates;
		private int executions;
		private Instant nextEligibility;

		void observe(Instant eligibility) {
			requireNonNull(eligibility, "eligibility must not be null");
			if (nextEligibility == null || eligibility.isBefore(nextEligibility)) {
				nextEligibility = eligibility;
			}
		}

		ConsumptionOrchestrationResult idle() {
			return new ConsumptionOrchestrationResult.Idle(next(), counters());
		}

		ConsumptionOrchestrationResult exhausted(BudgetLimit limit) {
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

	private static final class RuntimeFailureSignal extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final RuntimeException failure;
		RuntimeFailureSignal(RuntimeException failure) { this.failure = failure; }
	}
}
