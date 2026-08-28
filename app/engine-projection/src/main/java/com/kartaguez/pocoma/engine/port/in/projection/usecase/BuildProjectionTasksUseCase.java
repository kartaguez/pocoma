package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;

/**
 * Legacy projection-task creation entry point, retained until the materialization worker delegates
 * to {@code engine-task-creation}.
 */
public interface BuildProjectionTasksUseCase {

	void buildProjectionTask(BuildProjectionTaskCommand command);
}
