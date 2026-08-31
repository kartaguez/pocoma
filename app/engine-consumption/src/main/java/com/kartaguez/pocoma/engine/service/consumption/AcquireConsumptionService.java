package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;

public final class AcquireConsumptionService implements AcquireConsumptionUseCase {

	private final ConsumptionLifecyclePersistencePort persistence;
	private final Clock clock;

	public AcquireConsumptionService(ConsumptionLifecyclePersistencePort persistence, Clock clock) {
		this.persistence = requireNonNull(persistence, "persistence must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public AcquireResult acquire(AcquireConsumptionInput input) {
		requireNonNull(input, "input must not be null");
		return persistence.acquire(
				input.consumptionKey(), ClaimId.generate(), input.workerId(), input.lease(), clock.instant());
	}
}
