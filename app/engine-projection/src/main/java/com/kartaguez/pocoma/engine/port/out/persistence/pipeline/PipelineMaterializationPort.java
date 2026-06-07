package com.kartaguez.pocoma.engine.port.out.persistence.pipeline;

import java.time.Instant;
import java.util.List;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.pipeline.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.model.pipeline.MaterializationResult;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.model.pipeline.TaskDescriptor;

public interface PipelineMaterializationPort {

	List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
			int limit,
			ProjectionPartition partition,
			Instant upperBound,
			List<PipelineDefinition> activePipelines);

	MaterializationResult materialize(
			EventPipelineMaterializationCandidate candidate,
			List<TaskDescriptor> tasks);

	MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate);

	MaterializationResult markFailed(
			EventPipelineMaterializationCandidate candidate,
			String failureKind,
			RuntimeException error);

	long countUnmaterialized(List<PipelineDefinition> activePipelines);
}
