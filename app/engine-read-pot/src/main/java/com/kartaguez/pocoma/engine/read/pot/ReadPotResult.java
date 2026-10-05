package com.kartaguez.pocoma.engine.read.pot;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;

public sealed interface ReadPotResult {
	record Ready(PotView pot) implements ReadPotResult {
		public Ready {
			requireNonNull(pot, "pot must not be null");
		}
	}

	record Forbidden() implements ReadPotResult {}

	record AuthFailed(ProjectionKey projectionKey) implements ReadPotResult {
		public AuthFailed {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}

	record AuthNotReady(ProjectionKey projectionKey) implements ReadPotResult {
		public AuthNotReady {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}

	record ReadPotFailed(ProjectionKey projectionKey) implements ReadPotResult {
		public ReadPotFailed {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}

	record ReadPotNotReady(ProjectionKey projectionKey) implements ReadPotResult {
		public ReadPotNotReady {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}
}
