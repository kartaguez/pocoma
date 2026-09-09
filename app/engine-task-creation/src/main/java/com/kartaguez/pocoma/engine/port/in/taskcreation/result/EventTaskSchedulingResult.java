package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

public sealed interface EventTaskSchedulingResult {
	UUID eventId();

	record Scheduled(UUID eventId, List<TaskCreationResult.Materialized> generations)
			implements EventTaskSchedulingResult {
		public Scheduled {
			requireNonNull(eventId, "eventId must not be null");
			generations = List.copyOf(requireNonNull(generations, "generations must not be null"));
		}
	}

	record Rejected(UUID eventId, String rejectionCode) implements EventTaskSchedulingResult {
		public Rejected {
			requireNonNull(eventId, "eventId must not be null");
			requireNonNull(rejectionCode, "rejectionCode must not be null");
			if (rejectionCode.isBlank()) throw new IllegalArgumentException("rejectionCode must not be blank");
		}
	}
}
