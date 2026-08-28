package com.kartaguez.pocoma.engine.port.out.taskcreation;

import java.util.List;

import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult;

/** Atomically records idempotence and zero to many durable tasks. */
@FunctionalInterface
public interface TaskCreationPort {
	TaskCreationResult createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> tasks);
}
