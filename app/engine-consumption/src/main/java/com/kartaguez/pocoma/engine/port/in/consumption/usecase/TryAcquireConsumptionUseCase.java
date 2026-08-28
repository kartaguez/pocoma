package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;

public interface TryAcquireConsumptionUseCase {

	Optional<Claim> tryAcquire(TryAcquireConsumptionInput input);
}
