package com.kartaguez.pocoma.engine.service.projection.pipeline;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.model.pipeline.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.model.pipeline.MaterializationResult;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineRegistry;
import com.kartaguez.pocoma.engine.model.pipeline.PipelineStrategy;
import com.kartaguez.pocoma.engine.model.pipeline.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.out.persistence.pipeline.PipelineMaterializationPort;

public final class MaterializeEventForPipelineService {

	private final PipelineRegistry pipelineRegistry;
	private final PipelineMaterializationPort materializationPort;

	public MaterializeEventForPipelineService(
			PipelineRegistry pipelineRegistry,
			PipelineMaterializationPort materializationPort) {
		this.pipelineRegistry = Objects.requireNonNull(pipelineRegistry, "pipelineRegistry must not be null");
		this.materializationPort = Objects.requireNonNull(materializationPort, "materializationPort must not be null");
	}

	public MaterializationResult materialize(EventPipelineMaterializationCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		try {
			PipelineStrategy strategy = pipelineRegistry.find(candidate.pipeline())
					.orElseThrow(() -> new IllegalArgumentException(
							"No pipeline strategy registered for " + candidate.pipeline()));
			if (!strategy.supports(candidate.event())) {
				return materializationPort.markSkipped(candidate);
			}
			List<TaskDescriptor> tasks = List.copyOf(strategy.materializeTasks(candidate.event()));
			return materializationPort.materialize(candidate, tasks);
		}
		catch (RuntimeException exception) {
			return materializationPort.markFailed(candidate, exception.getClass().getSimpleName(), exception);
		}
	}
}
