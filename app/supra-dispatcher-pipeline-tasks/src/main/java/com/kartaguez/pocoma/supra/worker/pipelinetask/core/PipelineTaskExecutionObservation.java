package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;

public interface PipelineTaskExecutionObservation {

	default void taskSubmitted(PipelineTask task) {
	}

	default void taskSucceeded(PipelineTask task, long durationNanos) {
	}

	default void taskFailed(PipelineTask task, long durationNanos) {
	}

	default void tasksClaimed(int count) {
	}
}
