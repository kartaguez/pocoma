package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;

/**
 * Legacy durable projection-task entry point, retained until the task worker invokes typed task
 * execution.
 */
public interface ExecuteProjectionTasksUseCase {

	void executeProjectionTask(ExecuteProjectionTaskCommand command);
}
