package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

public final class ObserveSourceVersionService implements ObserveSourceVersionUseCase {
	private final SourceVersionWatermarkPersistencePort persistence;

	public ObserveSourceVersionService(SourceVersionWatermarkPersistencePort persistence) {
		this.persistence = requireNonNull(persistence, "persistence must not be null");
	}

	@Override
	public SourceVersionObservation observe(ObserveSourceVersionInput input) {
		return requireNonNull(persistence.observe(requireNonNull(input, "input must not be null")),
				"persistence result must not be null");
	}
}
