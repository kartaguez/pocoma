package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;

/** Target acquisition result, introduced alongside the legacy result for staged migration. */
public sealed interface AcquireResult {

	record Acquired(Claim claim) implements AcquireResult {
		public Acquired {
			requireNonNull(claim, "claim must not be null");
		}
	}

	record Busy(Instant leaseUntil) implements AcquireResult {
		public Busy {
			requireNonNull(leaseUntil, "leaseUntil must not be null");
		}
	}

	record NotReady(Instant nextClaimAt) implements AcquireResult {
		public NotReady {
			requireNonNull(nextClaimAt, "nextClaimAt must not be null");
		}
	}

	record AlreadyDone(TerminalOutcome outcome, Optional<TerminalReason> reason) implements AcquireResult {
		public AlreadyDone {
			requireNonNull(outcome, "outcome must not be null");
			reason = requireNonNull(reason, "reason must not be null");
			if (reason.isPresent() != (outcome != TerminalOutcome.SUCCESS)) {
				throw new IllegalArgumentException("terminal outcome and reason are inconsistent");
			}
		}
	}
}
