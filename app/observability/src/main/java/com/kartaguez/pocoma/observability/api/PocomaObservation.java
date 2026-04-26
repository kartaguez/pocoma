package com.kartaguez.pocoma.observability.api;

import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;

public interface PocomaObservation {

	default void commandCommitted(String operation, String eventType, long requestStartedAtNanos, long committedAtNanos) {
	}

	default void eventSubmitted(ProjectionObservationContext context, long submittedAtNanos) {
	}

	default Scope openProjectionScope(ProjectionObservationContext context) {
		return Scope.NOOP;
	}

	default void projectionStarted(ProjectionObservationContext context, long startedAtNanos) {
	}

	default void projectionSucceeded(ProjectionObservationContext context, long startedAtNanos, long completedAtNanos) {
	}

	default void projectionFailed(ProjectionObservationContext context, long startedAtNanos, long failedAtNanos) {
	}

	default void projectionRetry(ProjectionObservationContext context, int attempt) {
	}

	@FunctionalInterface
	interface Scope extends AutoCloseable {

		Scope NOOP = () -> {
		};

		@Override
		void close();
	}
}
