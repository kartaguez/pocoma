package com.kartaguez.pocoma.engine.service.taskcreation;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationPlan;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.PlanTasksForEventInput;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.PlanTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.taskcreation.TaskCreationPort;

public final class CreateTasksForEventService implements CreateTasksForEventUseCase {

	private final PlanTasksForEventUseCase planTasksUseCase;
	private final TaskCreationPort taskCreationPort;

	public CreateTasksForEventService(
			PlanTasksForEventUseCase planTasksUseCase,
			TaskCreationPort taskCreationPort) {
		this.planTasksUseCase = requireNonNull(planTasksUseCase, "planTasksUseCase must not be null");
		this.taskCreationPort = requireNonNull(taskCreationPort, "taskCreationPort must not be null");
	}

	@Override
	public TaskCreationResult createTasks(CreateTasksForEventInput input) {
		requireNonNull(input, "input must not be null");
		TaskCreationPlan plan = planTasksUseCase.planTasks(
				new PlanTasksForEventInput(input.recordedEvent().event(), input.pipeline()));
		return taskCreationPort.createIfAbsent(
				new EventPipelineTaskCreation(input.recordedEvent(), input.pipeline()), plan.tasks());
	}
}
