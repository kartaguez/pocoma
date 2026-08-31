package com.kartaguez.pocoma.orchestrator.consumption.locator;

import java.util.Optional;

/**
 * One short-lived candidate cursor. It must not retain a transaction, lock or JPA session
 * between {@link #next()} and acquisition, and is closed before acquired work executes.
 */
public interface ConsumptionSearch extends AutoCloseable {
	Optional<LocatedConsumption> next();

	@Override
	default void close() {
	}
}
