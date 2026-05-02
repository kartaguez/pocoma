package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;

public interface BuildProjectionTasksUseCase {

	void buildProjectionTask(BuildProjectionTaskCommand command);
}
