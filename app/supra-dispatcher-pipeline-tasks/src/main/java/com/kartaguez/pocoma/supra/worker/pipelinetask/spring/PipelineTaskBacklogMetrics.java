package com.kartaguez.pocoma.supra.worker.pipelinetask.spring;

import java.util.Objects;

import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutorWorker;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskWorkSource;

final class PipelineTaskBacklogMetrics {

	private final PipelineTaskWorkSource workSource;
	private final PipelineTaskExecutorWorker worker;

	PipelineTaskBacklogMetrics(PipelineTaskWorkSource workSource, PipelineTaskExecutorWorker worker) {
		this.workSource = Objects.requireNonNull(workSource, "workSource must not be null");
		this.worker = Objects.requireNonNull(worker, "worker must not be null");
	}

	double pendingOrInProgress() {
		return workSource.countPendingOrInProgress(worker.claimCriteria());
	}

	double failed() {
		return workSource.countFailed(worker.claimCriteria());
	}
}
