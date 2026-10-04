package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

public sealed interface ProjectionPreparationOutcome {
	record Prepared(ValidatedProjection projection) implements ProjectionPreparationOutcome {
		public Prepared { requireNonNull(projection, "projection must not be null"); }
	}
	record Temporary(ProcessingFailure failure) implements ProjectionPreparationOutcome {
		public Temporary { requireNonNull(failure, "failure must not be null"); }
	}
	record Terminal(ProcessingFailure failure) implements ProjectionPreparationOutcome {
		public Terminal { requireNonNull(failure, "failure must not be null"); }
	}
}
