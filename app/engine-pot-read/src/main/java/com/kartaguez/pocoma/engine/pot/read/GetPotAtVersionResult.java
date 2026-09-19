package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

public sealed interface GetPotAtVersionResult {

	record Ready(PotView pot) implements GetPotAtVersionResult {
		public Ready {
			requireNonNull(pot, "pot must not be null");
		}
	}

	record Forbidden() implements GetPotAtVersionResult {
	}

	record AuthFailed() implements GetPotAtVersionResult {
	}

	record AuthNotReady() implements GetPotAtVersionResult {
	}

	record ReadPotFailed() implements GetPotAtVersionResult {
	}

	record ReadPotNotReady() implements GetPotAtVersionResult {
	}
}
