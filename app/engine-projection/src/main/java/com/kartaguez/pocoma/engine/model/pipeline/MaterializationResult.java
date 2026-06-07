package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.Objects;

public record MaterializationResult(
		EventPipelineMaterializationCandidate candidate,
		MaterializationOutcome outcome) {

	public MaterializationResult {
		Objects.requireNonNull(candidate, "candidate must not be null");
		Objects.requireNonNull(outcome, "outcome must not be null");
	}

	public static MaterializationResult materialized(EventPipelineMaterializationCandidate candidate) {
		return new MaterializationResult(candidate, MaterializationOutcome.MATERIALIZED);
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
