package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseCommandInput;

@FunctionalInterface
public interface ReleaseCommandUseCase {
	ConsumptionOutcome release(ReleaseCommandInput input);
}
