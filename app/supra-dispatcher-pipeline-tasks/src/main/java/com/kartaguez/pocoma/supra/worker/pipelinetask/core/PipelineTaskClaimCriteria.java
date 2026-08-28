package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.taskexecution.model.ConfiguredTaskExecutionBinding;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;

public record PipelineTaskClaimCriteria(
		ProjectionPartition partition,
		List<ConfiguredTaskExecutionBinding> activeBindings) {

	public PipelineTaskClaimCriteria {
		Objects.requireNonNull(partition, "partition must not be null");
		Objects.requireNonNull(activeBindings, "activeBindings must not be null");
		activeBindings = List.copyOf(activeBindings);
	}
}
