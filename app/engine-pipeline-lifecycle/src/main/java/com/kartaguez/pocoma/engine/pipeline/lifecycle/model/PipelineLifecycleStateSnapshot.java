package com.kartaguez.pocoma.engine.pipeline.lifecycle.model;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

public record PipelineLifecycleStateSnapshot(
		List<PipelineDefinition> activations, List<ServingSelection> servingSelections) {
	public PipelineLifecycleStateSnapshot {
		activations = List.copyOf(requireNonNull(activations, "activations must not be null"));
		servingSelections = List.copyOf(requireNonNull(servingSelections, "servingSelections must not be null"));
	}
}
