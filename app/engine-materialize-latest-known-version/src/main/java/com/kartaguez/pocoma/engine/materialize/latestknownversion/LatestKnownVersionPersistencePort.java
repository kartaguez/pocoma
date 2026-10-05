package com.kartaguez.pocoma.engine.materialize.latestknownversion;

public interface LatestKnownVersionPersistencePort {
	LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input);
}
