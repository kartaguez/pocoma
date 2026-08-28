package com.kartaguez.pocoma.engine.port.in.processing.event.usecase;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;

@FunctionalInterface
public interface ClaimNextEventUseCase {
	Optional<EventClaimResult> claimNext(ClaimNextEventInput input);
}
