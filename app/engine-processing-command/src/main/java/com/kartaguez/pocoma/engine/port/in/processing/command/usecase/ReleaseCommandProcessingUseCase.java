package com.kartaguez.pocoma.engine.port.in.processing.command.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ReleaseCommandProcessingInput;

public interface ReleaseCommandProcessingUseCase {

	ConsumptionOutcome release(ReleaseCommandProcessingInput input);
}
