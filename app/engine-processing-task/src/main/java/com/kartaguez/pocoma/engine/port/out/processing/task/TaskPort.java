package com.kartaguez.pocoma.engine.port.out.processing.task;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.engine.port.out.processing.task.model.RecordedTask;

/** Authoritative structural reload of a durable Task. */
public interface TaskPort {
	Optional<RecordedTask> findById(UUID taskId);
}
