package com.kartaguez.pocoma.engine.port.out.projection;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public interface ProjectionReadPort {
	Optional<Projection> findProjection(ProjectionKey key);

	boolean hasFailure(ProjectionKey key);
}
