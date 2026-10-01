package com.kartaguez.pocoma.engine.port.in.projection.read;

import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public interface ExactProjectionReadUseCase {
	ProjectionReadResult get(ProjectionKey key, ProjectionDefinition definition);
}
