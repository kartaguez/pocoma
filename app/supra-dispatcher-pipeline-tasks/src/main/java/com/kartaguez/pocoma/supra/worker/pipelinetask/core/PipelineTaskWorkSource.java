package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import com.kartaguez.pocoma.domain.pipeline.task.PipelineTask;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;

public interface PipelineTaskWorkSource extends ClaimableWorkLifecycle<PipelineTask, PipelineTaskClaimCriteria> {

	long countPendingOrInProgress(PipelineTaskClaimCriteria criteria);

	long countFailed(PipelineTaskClaimCriteria criteria);
}
