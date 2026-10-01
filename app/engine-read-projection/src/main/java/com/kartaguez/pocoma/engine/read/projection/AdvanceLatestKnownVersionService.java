package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

public final class AdvanceLatestKnownVersionService implements AdvanceLatestKnownVersionUseCase {
	private final LatestKnownVersionPersistencePort persistence;

	public AdvanceLatestKnownVersionService(LatestKnownVersionPersistencePort persistence) {
		this.persistence = requireNonNull(persistence, "persistence must not be null");
	}

	@Override
	public LatestKnownVersionUpdate advanceToAtLeast(AdvanceLatestKnownVersionInput input) {
		return requireNonNull(persistence.advanceToAtLeast(requireNonNull(input, "input must not be null")),
				"persistence result must not be null");
	}
}
