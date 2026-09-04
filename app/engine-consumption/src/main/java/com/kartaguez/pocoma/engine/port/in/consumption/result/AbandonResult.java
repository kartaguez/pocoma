package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;

public sealed interface AbandonResult {

	record Abandoned() implements AbandonResult {
	}

	record AlreadyDone(TerminalOutcome outcome, Optional<TerminalReason> reason) implements AbandonResult {
		public AlreadyDone {
			requireNonNull(outcome, "outcome must not be null");
			reason = requireNonNull(reason, "reason must not be null");
			if (reason.isPresent() != (outcome != TerminalOutcome.SUCCESS)) {
				throw new IllegalArgumentException("terminal outcome and reason are inconsistent");
			}
		}
	}
}
