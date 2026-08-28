package com.kartaguez.pocoma.engine.port.in.taskcreation.usecase;

import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationPlan;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.PlanTasksForEventInput;

@FunctionalInterface
public interface PlanTasksForEventUseCase {
	TaskCreationPlan planTasks(PlanTasksForEventInput input);
}
