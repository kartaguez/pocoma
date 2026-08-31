package com.kartaguez.pocoma.engine.port.in.consumption.result;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;

public sealed interface AbandonResult {

	record Abandoned() implements AbandonResult {
	}

	record AlreadyDone(TerminalOutcome outcome) implements AbandonResult {
		public AlreadyDone {
			requireNonNull(outcome, "outcome must not be null");
		}
	}
}
