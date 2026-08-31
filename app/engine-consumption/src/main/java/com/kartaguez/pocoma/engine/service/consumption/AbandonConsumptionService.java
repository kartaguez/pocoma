package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.engine.port.in.consumption.input.AbandonConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AbandonConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;

public final class AbandonConsumptionService implements AbandonConsumptionUseCase {

	private final ConsumptionLifecyclePersistencePort persistence;
	private final Clock clock;

	public AbandonConsumptionService(ConsumptionLifecyclePersistencePort persistence, Clock clock) {
		this.persistence = requireNonNull(persistence, "persistence must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public AbandonResult abandon(AbandonConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		return persistence.abandon(input.slotId(), clock.instant());
	}
}
