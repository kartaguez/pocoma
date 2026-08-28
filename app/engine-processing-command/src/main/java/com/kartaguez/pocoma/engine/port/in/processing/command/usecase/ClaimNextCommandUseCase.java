package com.kartaguez.pocoma.engine.port.in.processing.command.usecase;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;

public interface ClaimNextCommandUseCase {

	Optional<CommandClaimResult> claimNext(ClaimNextCommandInput input);
}
