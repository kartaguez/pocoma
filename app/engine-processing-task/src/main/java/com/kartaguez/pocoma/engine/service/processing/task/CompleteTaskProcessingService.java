package com.kartaguez.pocoma.engine.service.processing.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.CompleteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.task.input.CompleteTaskProcessingInput;
import com.kartaguez.pocoma.engine.port.in.processing.task.usecase.CompleteTaskProcessingUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.task.TaskPort;

public final class CompleteTaskProcessingService implements CompleteTaskProcessingUseCase {

	private final TaskPort taskPort;
	private final CompleteConsumptionUseCase completeConsumptionUseCase;

	public CompleteTaskProcessingService(
			TaskPort taskPort,
			CompleteConsumptionUseCase completeConsumptionUseCase) {
		this.taskPort = requireNonNull(taskPort, "taskPort must not be null");
		this.completeConsumptionUseCase = requireNonNull(
				completeConsumptionUseCase, "completeConsumptionUseCase must not be null");
	}

	@Override
	public ConsumptionOutcome complete(CompleteTaskProcessingInput input) {
		requireNonNull(input, "input must not be null");
		ConsumptionOutcome outcome = completeConsumptionUseCase.complete(new CompleteConsumptionInput(
				TaskProcessingKeys.forTask(input.taskId()), input.claimToken()));
		if (outcome == ConsumptionOutcome.APPLIED) {
			taskPort.markCompleted(input.taskId());
		}
		return outcome;
	}
}
