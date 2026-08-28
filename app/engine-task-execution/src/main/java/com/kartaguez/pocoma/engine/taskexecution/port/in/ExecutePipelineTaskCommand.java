package com.kartaguez.pocoma.engine.taskexecution.port.in;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

/** Transitional input carrying durable worker state. Use {@code ExecuteTaskInput} for direct execution. */
public record ExecutePipelineTaskCommand(PipelineTask task) {

	public ExecutePipelineTaskCommand {
		Objects.requireNonNull(task, "task must not be null");
	}
}
