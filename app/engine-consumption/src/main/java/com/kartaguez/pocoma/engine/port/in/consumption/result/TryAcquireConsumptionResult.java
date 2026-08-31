package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

/** Exhaustive outcome of an attempt to acquire one logical consumption. */
@Deprecated(forRemoval = true)
public sealed interface TryAcquireConsumptionResult {

	record Acquired(Claim claim) implements TryAcquireConsumptionResult {
		public Acquired {
			requireNonNull(claim, "claim must not be null");
		}
	}

	record NotAcquiredBusy() implements TryAcquireConsumptionResult {
	}

	record AlreadyCompleted() implements TryAcquireConsumptionResult {
	}

	record AlreadyFailed(ProcessingFailure failure) implements TryAcquireConsumptionResult {
		public AlreadyFailed {
			requireNonNull(failure, "failure must not be null");
		}
	}
}
