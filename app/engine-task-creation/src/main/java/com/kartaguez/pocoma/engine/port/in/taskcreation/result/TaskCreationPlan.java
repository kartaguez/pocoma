package com.kartaguez.pocoma.engine.port.in.taskcreation.result;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.event.BusinessEvent;

public record TaskCreationPlan(
		BusinessEvent event,
		PipelineDefinition pipeline,
		List<TaskDescriptor> tasks) {

	public TaskCreationPlan {
		requireNonNull(event, "event must not be null");
		requireNonNull(pipeline, "pipeline must not be null");
		tasks = List.copyOf(requireNonNull(tasks, "tasks must not be null"));
	}
}
