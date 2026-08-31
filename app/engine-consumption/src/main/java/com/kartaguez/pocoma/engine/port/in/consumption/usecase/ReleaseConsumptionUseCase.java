package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;

@Deprecated(forRemoval = true)
public interface ReleaseConsumptionUseCase {

	ConsumptionOutcome release(ReleaseConsumptionInput input);
}
