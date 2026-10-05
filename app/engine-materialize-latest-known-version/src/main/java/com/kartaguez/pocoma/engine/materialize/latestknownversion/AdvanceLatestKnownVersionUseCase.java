package com.kartaguez.pocoma.engine.materialize.latestknownversion;

public interface AdvanceLatestKnownVersionUseCase {
	LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input);
}
