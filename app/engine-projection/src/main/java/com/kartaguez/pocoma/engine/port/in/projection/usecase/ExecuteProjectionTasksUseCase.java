package com.kartaguez.pocoma.engine.port.in.projection.usecase;

import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;

public interface ExecuteProjectionTasksUseCase {

	void executeProjectionTask(ExecuteProjectionTaskCommand command);
}
