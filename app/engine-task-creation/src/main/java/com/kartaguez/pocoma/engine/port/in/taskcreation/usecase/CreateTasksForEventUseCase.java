package com.kartaguez.pocoma.engine.port.in.taskcreation.usecase;

import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.CreateTasksForEventInput;

@FunctionalInterface
public interface CreateTasksForEventUseCase {
	TaskCreationResult createTasks(CreateTasksForEventInput input);
}
