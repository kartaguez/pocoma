package com.kartaguez.pocoma.engine.taskmaterialization.service;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineStrategy;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksCommand;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksUseCase;
import com.kartaguez.pocoma.engine.taskmaterialization.port.out.TaskMaterializationPort;

public final class MaterializeTasksService implements MaterializeTasksUseCase {

	private final PipelineRegistry pipelineRegistry;
	private final TaskMaterializationPort materializationPort;

	public MaterializeTasksService(
			PipelineRegistry pipelineRegistry,
			TaskMaterializationPort materializationPort) {
		this.pipelineRegistry = Objects.requireNonNull(pipelineRegistry, "pipelineRegistry must not be null");
		this.materializationPort = Objects.requireNonNull(materializationPort, "materializationPort must not be null");
	}

	@Override
	public MaterializationResult materializeTasks(MaterializeTasksCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		EventPipelineMaterializationCandidate candidate = new EventPipelineMaterializationCandidate(
				command.event(),
				command.pipeline());
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
