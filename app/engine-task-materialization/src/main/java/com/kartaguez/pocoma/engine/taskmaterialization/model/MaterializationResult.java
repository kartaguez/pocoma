package com.kartaguez.pocoma.engine.taskmaterialization.model;

import java.util.Objects;

public record MaterializationResult(
		EventPipelineMaterializationCandidate candidate,
		MaterializationOutcome outcome,
		int taskCount) {

	public MaterializationResult {
		Objects.requireNonNull(candidate, "candidate must not be null");
		Objects.requireNonNull(outcome, "outcome must not be null");
		if (taskCount < 0) {
			throw new IllegalArgumentException("taskCount must be greater than or equal to 0");
		}
	}

	public MaterializationResult(
			EventPipelineMaterializationCandidate candidate,
			MaterializationOutcome outcome) {
		this(candidate, outcome, 0);
	}

	public static MaterializationResult materialized(EventPipelineMaterializationCandidate candidate) {
		return materialized(candidate, 0);
	}

	public static MaterializationResult materialized(EventPipelineMaterializationCandidate candidate, int taskCount) {
		return new MaterializationResult(candidate, MaterializationOutcome.MATERIALIZED, taskCount);
	}

	public static MaterializationResult skipped(EventPipelineMaterializationCandidate candidate) {
		return new MaterializationResult(candidate, MaterializationOutcome.SKIPPED);
	}

	public static MaterializationResult alreadyMaterialized(EventPipelineMaterializationCandidate candidate) {
		return new MaterializationResult(candidate, MaterializationOutcome.ALREADY_MATERIALIZED);
	}

	public static MaterializationResult failed(EventPipelineMaterializationCandidate candidate) {
		return new MaterializationResult(candidate, MaterializationOutcome.FAILED);
	}
}
