package com.kartaguez.pocoma.engine.read.projection;

public interface AdvanceLatestKnownVersionUseCase {
	LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input);
}
