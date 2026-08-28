package com.kartaguez.pocoma.engine.taskmaterialization.port.out;

import java.util.List;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;

public interface TaskMaterializationPort {

	MaterializationResult materialize(
			EventPipelineMaterializationCandidate candidate,
			List<TaskDescriptor> tasks);

	MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate);

	MaterializationResult markFailed(
			EventPipelineMaterializationCandidate candidate,
			String failureKind,
			RuntimeException error);
}
