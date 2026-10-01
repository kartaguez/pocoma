package com.kartaguez.pocoma.engine.port.out.projection;

import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

public interface ProjectionWritePort {
	ProjectionPublicationResult publish(ValidatedProjection projection);

	void recordFailure(ProjectionFailure failure);
}
