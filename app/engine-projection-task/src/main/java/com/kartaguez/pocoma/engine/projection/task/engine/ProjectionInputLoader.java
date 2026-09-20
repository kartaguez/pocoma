package com.kartaguez.pocoma.engine.projection.task.engine;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ProjectionInputLoader<I> {
	I load(ProjectionKey key);
}
