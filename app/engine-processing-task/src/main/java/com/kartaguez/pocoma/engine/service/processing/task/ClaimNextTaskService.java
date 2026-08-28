package com.kartaguez.pocoma.engine.service.processing.task;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.ClaimNextTaskInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.result.TaskClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.ClaimNextTaskUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;
import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;
import com.kartaguez.pocoma.engine.processing.task.ordering.TaskOrderingKey;

public final class ClaimNextTaskService implements ClaimNextTaskUseCase {

	private final TaskPort taskPort;
	private final TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase;

	public ClaimNextTaskService(TaskPort taskPort, TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase) {
		this.taskPort = requireNonNull(taskPort, "taskPort must not be null");
		this.tryAcquireConsumptionUseCase = requireNonNull(
				tryAcquireConsumptionUseCase, "tryAcquireConsumptionUseCase must not be null");
	}

	@Override
	public Optional<TaskClaimResult> claimNext(ClaimNextTaskInput input) {
		requireNonNull(input, "input must not be null");
		Optional<TaskOrderingKey> cursor = Optional.empty();
		while (true) {
			Optional<RecordedTask> candidate = taskPort.findNextReady(
					input.pipeline(), input.segment(), cursor);
			if (candidate.isEmpty()) {
				return Optional.empty();
			}
			RecordedTask task = candidate.orElseThrow();
			Optional<Claim> claim = tryAcquireConsumptionUseCase.tryAcquire(
					new TryAcquireConsumptionInput(
							TaskProcessingKeys.forTask(task.taskId()), input.workerId(), input.lease()));
			if (claim.isPresent()) {
				return Optional.of(new TaskClaimResult(task, claim.orElseThrow()));
			}
			cursor = Optional.of(new TaskOrderingKey(task.targetVersion(), task.createdAt(), task.taskId()));
		}
	}
}
