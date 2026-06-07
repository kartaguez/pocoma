package com.kartaguez.pocoma.engine.taskmaterialization.port.in;

import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;

public interface MaterializeTasksUseCase {

	MaterializationResult materializeTasks(MaterializeTasksCommand command);
}
