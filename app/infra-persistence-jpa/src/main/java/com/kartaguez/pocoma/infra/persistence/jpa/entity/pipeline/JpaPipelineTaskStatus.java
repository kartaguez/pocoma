package com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline;

/** Persistence state of the legacy pipeline-task worker. */
public enum JpaPipelineTaskStatus {
	PENDING,
	CLAIMED,
	ACCEPTED,
	RUNNING,
	DONE,
	FAILED,
	SUPERSEDED
}
