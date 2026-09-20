package com.kartaguez.pocoma.engine.projection.task.engine;

import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;

@FunctionalInterface
public interface ExecuteProjectionTaskUseCase {
	ProjectionPreparationOutcome execute(ProjectionTask task);
}
