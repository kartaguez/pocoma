package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;
import com.kartaguez.pocoma.domain.projection.ProjectionStatus;

public sealed interface ProjectionResolution {
	record NotExpected() implements ProjectionResolution {}
	record Resolved(ProjectionStatus status) implements ProjectionResolution {
		public Resolved { requireNonNull(status); }
	}
}
