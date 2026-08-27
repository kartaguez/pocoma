package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailCommandInput;

@FunctionalInterface
public interface FailCommandUseCase {
	ConsumptionOutcome fail(FailCommandInput input);
}
