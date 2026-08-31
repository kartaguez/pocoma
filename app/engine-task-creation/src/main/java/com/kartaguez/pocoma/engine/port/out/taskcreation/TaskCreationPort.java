package com.kartaguez.pocoma.engine.port.out.taskcreation;

import java.util.List;

import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.engine.port.out.taskcreation.input.EventPipelineTaskCreation;
import com.kartaguez.pocoma.engine.port.in.taskcreation.result.TaskCreationResult.Materialized;

/** Atomically records idempotence and zero to many durable tasks. */
@FunctionalInterface
public interface TaskCreationPort {
	Materialized createIfAbsent(EventPipelineTaskCreation creation, List<TaskDescriptor> tasks);
}
