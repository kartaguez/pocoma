package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public sealed interface ReadPotResult {
	record Ready(PotView pot) implements ReadPotResult {
		public Ready {
			requireNonNull(pot, "pot must not be null");
		}
	}

	record Failed(ProjectionKey projectionKey) implements ReadPotResult {
		public Failed {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}

	record NotReady(ProjectionKey projectionKey) implements ReadPotResult {
		public NotReady {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}
}
