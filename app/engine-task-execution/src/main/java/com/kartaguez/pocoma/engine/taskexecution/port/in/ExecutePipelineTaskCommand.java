package com.kartaguez.pocoma.engine.taskexecution.port.in;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

public record ExecutePipelineTaskCommand(PipelineTask task) {

	public ExecutePipelineTaskCommand {
		Objects.requireNonNull(task, "task must not be null");
	}
}
