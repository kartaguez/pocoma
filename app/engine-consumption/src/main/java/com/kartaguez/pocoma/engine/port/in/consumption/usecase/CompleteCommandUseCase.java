package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteCommandInput;

@FunctionalInterface
public interface CompleteCommandUseCase {
	ConsumptionOutcome complete(CompleteCommandInput input);
}
