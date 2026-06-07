package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import java.time.Instant;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;

public record TaskMaterializationEventObservation(
		EventPipelineMaterializationCandidate candidate,
		Instant completedAt,
		long durationNanos) {

	public TaskMaterializationEventObservation {
		if (durationNanos < 0) {
			throw new IllegalArgumentException("durationNanos must be greater than or equal to 0");
		}
	}
}
