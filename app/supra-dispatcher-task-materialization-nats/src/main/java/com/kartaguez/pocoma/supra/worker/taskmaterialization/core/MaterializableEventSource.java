package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import java.time.Instant;
import java.util.List;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;

public interface MaterializableEventSource {

	List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
			int limit,
			ProjectionPartition partition,
			Instant upperBound,
			List<ConfiguredPipelineBinding> activeBindings);

	long countUnmaterialized(List<ConfiguredPipelineBinding> activeBindings);
}
