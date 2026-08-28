package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;

public interface PipelineTaskExecutionObservation {

	default void taskSubmitted(LegacyPipelineTask task) {
	}

	default void taskSucceeded(LegacyPipelineTask task, long durationNanos) {
	}

	default void taskFailed(LegacyPipelineTask task, long durationNanos) {
	}

	default void tasksClaimed(int count) {
	}
}
