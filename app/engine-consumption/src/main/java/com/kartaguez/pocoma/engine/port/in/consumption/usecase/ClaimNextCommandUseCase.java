package com.kartaguez.pocoma.engine.port.in.consumption.usecase;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.consumption.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.CommandClaimResult;

@FunctionalInterface
public interface ClaimNextCommandUseCase {
	Optional<CommandClaimResult> claimNext(ClaimNextCommandInput input);
}
