package com.kartaguez.pocoma.engine.port.in.consumption.contract;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public sealed interface ConsumptionFinalization {
	record Rejected(String rejectionCode) implements ConsumptionFinalization {
		public Rejected {
			requireNonNull(rejectionCode, "rejectionCode must not be null");
			if (rejectionCode.isBlank()) throw new IllegalArgumentException("rejectionCode must not be blank");
		}
	}

	record Success() implements ConsumptionFinalization {}

	record TerminalFailure(ProcessingFailure failure) implements ConsumptionFinalization {
		public TerminalFailure {
			requireNonNull(failure, "failure must not be null");
		}
	}
}
