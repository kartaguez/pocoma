package com.kartaguez.pocoma.engine.projection.task;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ProjectionTaskPreparation {
	ProjectionPreparationOutcome prepare(ProjectionKey key);
}
