package com.kartaguez.pocoma.engine.port.in.taskcreation.strategy;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Pipeline-specific, persistence-free transformation of a typed event into task descriptors. */
public interface TaskCreationStrategy {

	PipelineDefinition definition();

	boolean supports(BusinessEvent event);

	List<TaskDescriptor> createTasks(BusinessEvent event);
}
