package com.kartaguez.pocoma.locator.consumption.task;

import java.util.UUID;

import com.kartaguez.pocoma.engine.taskexecution.model.NonRetryableTaskTechnicalFailure;

public final class TaskRecordedInputNotFoundException extends RuntimeException
		implements NonRetryableTaskTechnicalFailure {
	public TaskRecordedInputNotFoundException(UUID taskId) {
		super("Recorded Task was not found during authoritative execution: " + taskId);
	}
	@Override public String failureCode() { return "RECORDED_TASK_NOT_FOUND"; }
	@Override public String failureCategory() { return "TASK_INPUT_NOT_FOUND"; }
}
