package com.kartaguez.pocoma.engine.consume.projectiontask.engine;

import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.port.projection.task.ProjectionTask;

@FunctionalInterface
public interface ExecuteProjectionTaskUseCase {
	ProjectionPreparationOutcome execute(ProjectionTask task);
}
