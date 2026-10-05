package com.kartaguez.pocoma.engine.read.projection.port;

import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public interface ExactProjectionReadUseCase {
	ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition);
}
