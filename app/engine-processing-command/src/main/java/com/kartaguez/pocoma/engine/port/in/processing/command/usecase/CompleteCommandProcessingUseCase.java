package com.kartaguez.pocoma.engine.port.in.processing.command.usecase;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.CompleteCommandProcessingInput;

public interface CompleteCommandProcessingUseCase {

	ConsumptionOutcome complete(CompleteCommandProcessingInput input);
}
