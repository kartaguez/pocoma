package com.kartaguez.pocoma.engine.projection.task.engine;

import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ProjectionProjector<I> {
	Projection project(ProjectionKey key, I input);
}
