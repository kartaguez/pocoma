package com.kartaguez.pocoma.engine.service.projection.task;

import java.util.Objects;

import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;

public final class BuildProjectionTasksService implements BuildProjectionTasksUseCase {

	private final ProjectionTaskPort projectionTaskPort;
	private final ProjectionEventPublisherPort eventPublisherPort;

	public BuildProjectionTasksService(
			ProjectionTaskPort projectionTaskPort,
			ProjectionEventPublisherPort eventPublisherPort) {
		this.projectionTaskPort = Objects.requireNonNull(projectionTaskPort, "projectionTaskPort must not be null");
		this.eventPublisherPort = Objects.requireNonNull(eventPublisherPort, "eventPublisherPort must not be null");
	}

	@Override
	public void buildProjectionTask(BuildProjectionTaskCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		ProjectionTaskDescriptor task = projectionTaskPort.upsertComputeBalancesTask(command.event());
		eventPublisherPort.publish(new ProjectionTasksReadyEvent(
				task.id(),
				task.potId(),
				task.targetVersion(),
				task.sourceEventType()));
	}
}
