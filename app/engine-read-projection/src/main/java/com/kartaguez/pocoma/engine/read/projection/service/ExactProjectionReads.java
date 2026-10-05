package com.kartaguez.pocoma.engine.read.projection.service;

import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.read.projection.port.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.port.projection.ProjectionReadPort;

public final class ExactProjectionReads {
	private ExactProjectionReads() {
	}

	public static ExactProjectionReadUseCase create(ProjectionReadPort readPort, ProjectionValidator validator) {
		return new ExactProjectionReadService(readPort, validator);
	}
}
