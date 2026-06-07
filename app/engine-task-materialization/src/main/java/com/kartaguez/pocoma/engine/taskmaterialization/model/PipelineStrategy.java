package com.kartaguez.pocoma.engine.taskmaterialization.model;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

public interface PipelineStrategy {

	PipelineDefinition definition();

	boolean supports(BusinessEventEnvelope event);

	List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event);
}
