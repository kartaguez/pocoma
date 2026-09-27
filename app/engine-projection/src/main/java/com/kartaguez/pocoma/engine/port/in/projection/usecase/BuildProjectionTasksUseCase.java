package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;

/**
 * Legacy projection-task creation entry point retained for the legacy Task runtime until PCL.3.
 */
public interface BuildProjectionTasksUseCase {

	void buildProjectionTask(BuildProjectionTaskCommand command);
}
