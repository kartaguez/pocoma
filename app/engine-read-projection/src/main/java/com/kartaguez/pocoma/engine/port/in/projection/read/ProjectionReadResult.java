package com.kartaguez.pocoma.engine.port.in.projection.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

public sealed interface ProjectionReadResult {
	ProjectionKey projectionKey();

	record Ready(ValidatedProjection projection) implements ProjectionReadResult {
		public Ready {
			requireNonNull(projection, "projection must not be null");
		}

		@Override
		public ProjectionKey projectionKey() {
			return projection.projection().projectionKey();
		}
	}

	record Failed(ProjectionKey projectionKey) implements ProjectionReadResult {
		public Failed {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}

	record NotReady(ProjectionKey projectionKey) implements ProjectionReadResult {
		public NotReady {
			requireNonNull(projectionKey, "projectionKey must not be null");
		}
	}
}
