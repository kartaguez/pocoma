package com.kartaguez.pocoma.engine.port.in.processing.task.usecase;

import java.util.Optional;

import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;

@FunctionalInterface
public interface ClaimNextTaskUseCase {
	Optional<TaskClaimResult> claimNext(ClaimNextTaskInput input);
}
