package com.kartaguez.pocoma.engine.pot.read;

import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;

public final class PotReads {
	private PotReads() {
	}

	public static ReadPotUseCase create(ExactProjectionReadUseCase projectionRead) {
		return new ReadPotService(projectionRead, new AuthProjectionInterpreter(),
				new ReadPotInterpreter());
	}
}
