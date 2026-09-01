package com.kartaguez.pocoma.pipeline.balance;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

public final class BalanceTaskCreationStrategy implements TaskCreationStrategy {
	private final PipelineDefinition pipeline;
	private final ObjectMapper mapper;

	public BalanceTaskCreationStrategy(PipelineDefinition pipeline, ObjectMapper mapper) {
		this.pipeline = requireNonNull(pipeline, "pipeline must not be null");
		if (!BalancePipeline.PIPELINE_ID.equals(pipeline.pipelineId().value())) {
			throw new IllegalArgumentException("pipeline must be the Balance pipeline");
		}
		this.mapper = requireNonNull(mapper, "mapper must not be null");
	}

	@Override public PipelineDefinition definition() { return pipeline; }
	@Override public boolean supports(BusinessEvent event) { return event != null; }

	@Override
	public List<TaskDescriptor> createTasks(BusinessEvent event) {
		requireNonNull(event, "event must not be null");
		String potId = event.potId().value().toString();
		return List.of(new TaskDescriptor(BalancePipeline.TASK_TYPE,
				potId + ":" + event.version(), payload(potId, event.version()), potId, event.version()));
	}

	private String payload(String potId, long targetVersion) {
		try { return mapper.writeValueAsString(new SerializedPayload(potId, targetVersion)); }
		catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to serialize Balance task", exception); }
	}

	private record SerializedPayload(String potId, long targetVersion) {}
}
