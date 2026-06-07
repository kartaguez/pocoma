package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import java.util.Objects;

import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.MaterializableEventSource;

final class TaskMaterializationBacklogMetrics {

	private final MaterializableEventSource eventSource;
	private final PipelineRegistry pipelineRegistry;

	TaskMaterializationBacklogMetrics(
			MaterializableEventSource eventSource,
			PipelineRegistry pipelineRegistry) {
		this.eventSource = Objects.requireNonNull(eventSource, "eventSource must not be null");
		this.pipelineRegistry = Objects.requireNonNull(pipelineRegistry, "pipelineRegistry must not be null");
	}

	double countUnmaterialized() {
		return eventSource.countUnmaterialized(pipelineRegistry.activeBindings());
	}
}
