package com.kartaguez.pocoma.engine.read.projection;

public interface LatestKnownVersionPersistencePort {
	LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input);
}
