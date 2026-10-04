package com.kartaguez.pocoma.engine.consume.projectiontask;

public enum ProjectionTaskExecutionResult {
	FINALIZED,
	RETRY_SCHEDULED,
	LOST_CLAIM
}
