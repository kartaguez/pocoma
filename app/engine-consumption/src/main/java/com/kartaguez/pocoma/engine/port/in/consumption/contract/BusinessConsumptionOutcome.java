package com.kartaguez.pocoma.engine.port.in.consumption.contract;

import static java.util.Objects.requireNonNull;

/** Business conclusion of a successfully executed consumption attempt. */
public sealed interface BusinessConsumptionOutcome {

	record Success() implements BusinessConsumptionOutcome {
	}

	record Rejected(String rejectionCode) implements BusinessConsumptionOutcome {
		public Rejected {
			requireNonNull(rejectionCode, "rejectionCode must not be null");
			if (rejectionCode.isBlank()) {
				throw new IllegalArgumentException("rejectionCode must not be blank");
			}
		}
	}
}
