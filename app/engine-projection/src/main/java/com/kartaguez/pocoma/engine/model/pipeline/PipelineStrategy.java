package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.List;

import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

public interface PipelineStrategy {

	PipelineDefinition definition();

	boolean supports(BusinessEventEnvelope event);

	List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event);
}
