package com.kartaguez.pocoma.domain.projection.projector;

import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

@FunctionalInterface
public interface ProjectionProjector<I> {
	Projection project(ProjectionKey key, I input);
}
