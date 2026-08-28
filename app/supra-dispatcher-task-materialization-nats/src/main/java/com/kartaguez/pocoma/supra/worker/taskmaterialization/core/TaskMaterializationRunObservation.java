package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;

public record TaskMaterializationRunObservation(
		String workerId,
		ProjectionPartition partition,
		int activePipelineCount,
		int selectedCandidateCount,
		long durationNanos) {

	public TaskMaterializationRunObservation {
		if (activePipelineCount < 0) {
			throw new IllegalArgumentException("activePipelineCount must be greater than or equal to 0");
		}
		if (selectedCandidateCount < 0) {
			throw new IllegalArgumentException("selectedCandidateCount must be greater than or equal to 0");
		}
		if (durationNanos < 0) {
			throw new IllegalArgumentException("durationNanos must be greater than or equal to 0");
		}
	}
}
