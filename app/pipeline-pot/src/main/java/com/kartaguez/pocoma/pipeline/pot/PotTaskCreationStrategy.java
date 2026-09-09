package com.kartaguez.pocoma.pipeline.pot;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

public final class PotTaskCreationStrategy implements TaskCreationStrategy {

	private final PipelineDefinition pipeline;
	private final ObjectMapper mapper;

	public PotTaskCreationStrategy(PipelineDefinition pipeline, ObjectMapper mapper) {
		this.pipeline = requireNonNull(pipeline);
		this.mapper = requireNonNull(mapper);
		if (!PotProjectionPipeline.PIPELINE_ID.equals(pipeline.pipelineId().value())) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public PipelineDefinition definition() {
		return pipeline;
	}

	@Override
	public boolean supports(BusinessEvent event) {
		return event != null;
	}

	@Override
	public List<TaskDescriptor> createTasks(BusinessEvent event) {
		requireNonNull(event);
		String potId = event.potId().value().toString();
		var payload = new Payload(
				pipeline.pipelineId().value(), pipeline.pipelineVersion(), potId, event.version());

		try {
			return List.of(new TaskDescriptor(
					PotProjectionPipeline.TASK_TYPE,
					potId + ":" + event.version(),
					mapper.writeValueAsString(payload),
					potId,
					event.version()));
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to serialize Pot task", exception);
		}
	}

	private record Payload(String pipelineId, int pipelineVersion, String potId, long potVersion) {
	}
}
