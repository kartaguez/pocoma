package com.kartaguez.pocoma.supra.worker.taskmaterialization.core;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;

@FunctionalInterface
public interface PipelineMaterializationCompletedListener {

	void onMaterializationCompleted(
			EventPipelineMaterializationCandidate candidate,
			MaterializationResult result);

	static PipelineMaterializationCompletedListener noop() {
		return (candidate, result) -> {
		};
	}
}
