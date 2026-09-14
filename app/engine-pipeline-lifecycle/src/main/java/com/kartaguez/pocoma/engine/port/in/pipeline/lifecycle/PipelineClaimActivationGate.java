package com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

/** Must be called inside the transaction that acquires the consumption Claim. */
public interface PipelineClaimActivationGate {
	boolean lockIfActive(PipelineDefinition pipeline);
}
