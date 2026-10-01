package com.kartaguez.pocoma.engine.service.projection.read;

import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;

public final class ExactProjectionReads {
	private ExactProjectionReads() {
	}

	public static ExactProjectionReadUseCase create(ProjectionReadPort readPort, ProjectionValidator validator) {
		return new ExactProjectionReadService(readPort, validator);
	}
}
