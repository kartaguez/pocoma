package com.kartaguez.pocoma.supra.worker.pipelinetask.core;

import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkLifecycle;

public interface PipelineTaskWorkSource extends ClaimableWorkLifecycle<LegacyPipelineTask, PipelineTaskClaimCriteria> {

	long countPendingOrInProgress(PipelineTaskClaimCriteria criteria);

	long countFailed(PipelineTaskClaimCriteria criteria);
}
