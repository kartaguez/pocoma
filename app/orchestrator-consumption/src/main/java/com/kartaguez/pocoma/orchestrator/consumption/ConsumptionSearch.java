package com.kartaguez.pocoma.orchestrator.consumption;

import java.util.Optional;

public interface ConsumptionSearch extends AutoCloseable {
	Optional<LocatedConsumption> next();

	@Override
	default void close() {
	}
}
