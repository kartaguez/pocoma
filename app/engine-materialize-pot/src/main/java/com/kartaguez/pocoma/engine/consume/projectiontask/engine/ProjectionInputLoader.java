package com.kartaguez.pocoma.engine.consume.projectiontask.engine;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ProjectionInputLoader<I> {
	I load(ProjectionKey key);
}
