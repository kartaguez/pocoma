package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;

/** Versioned optimistic-concurrency pivot for one consumption. */
public record ConsumptionSlot(ConsumptionKey consumptionKey, long revision, ConsumptionStatus status) {

	public ConsumptionSlot {
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		if (revision < 0) {
			throw new IllegalArgumentException("revision must not be negative");
		}
		requireNonNull(status, "status must not be null");
	}

	public static ConsumptionSlot initial(ConsumptionKey key) {
		return new ConsumptionSlot(key, 0, ConsumptionStatus.READY);
	}

	public ConsumptionSlot acquired() {
		return transitionTo(ConsumptionStatus.READY);
	}

	public ConsumptionSlot completed() {
		return transitionTo(ConsumptionStatus.COMPLETED);
	}

	public ConsumptionSlot failed() {
		return transitionTo(ConsumptionStatus.FAILED);
	}

	public ConsumptionSlot released() {
		return transitionTo(ConsumptionStatus.READY);
	}

	private ConsumptionSlot transitionTo(ConsumptionStatus target) {
		if (status != ConsumptionStatus.READY) {
			throw new IllegalStateException("a terminal consumption slot cannot transition");
		}
		return new ConsumptionSlot(consumptionKey, revision + 1, target);
	}
}
