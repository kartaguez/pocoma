package com.kartaguez.pocoma.domain.pipeline.task;

public enum PipelineTaskStatus {
	PENDING,
	CLAIMED,
	ACCEPTED,
	RUNNING,
	DONE,
	FAILED,
	SUPERSEDED
}
