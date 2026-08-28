package com.kartaguez.pocoma.engine.service.processing.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FailConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.FailTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.FailTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;

public final class FailTaskProcessingService implements FailTaskProcessingUseCase {

	private final TaskPort taskPort;
	private final FailConsumptionUseCase failConsumptionUseCase;

	public FailTaskProcessingService(TaskPort taskPort, FailConsumptionUseCase failConsumptionUseCase) {
		this.taskPort = requireNonNull(taskPort, "taskPort must not be null");
		this.failConsumptionUseCase = requireNonNull(
				failConsumptionUseCase, "failConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome fail(FailTaskProcessingInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = failConsumptionUseCase.fail(new FailConsumptionInput(
				TaskProcessingKeys.forTask(input.taskId()), input.claimToken(), input.failure()));
		if (outcome == ConsumptionOutcome.APPLIED) {
			taskPort.markFailed(input.taskId(), input.failure());
		}
		return outcome;
	}
}
