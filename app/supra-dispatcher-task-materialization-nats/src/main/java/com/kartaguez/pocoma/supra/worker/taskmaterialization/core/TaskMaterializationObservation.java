package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;

public interface TaskMaterializationObservation {

	default void runCompleted(TaskMaterializationRunObservation observation) {
	}

	default void materializationCompleted(
			TaskMaterializationEventObservation observation,
			MaterializationResult result) {
	}
}
