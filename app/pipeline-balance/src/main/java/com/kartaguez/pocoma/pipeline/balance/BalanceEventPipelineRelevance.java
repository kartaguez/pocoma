package com.kartaguez.pocoma.pipeline.balance;

import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.EventPipelineRelevance;

/** Every Pot business Event is relevant to the Balance projection family. */
public final class BalanceEventPipelineRelevance implements EventPipelineRelevance {
	private static final PipelineId PIPELINE_ID = PipelineId.of(BalancePipeline.PIPELINE_ID);

	@Override public PipelineId pipelineId() { return PIPELINE_ID; }
	@Override public boolean supports(BusinessEvent event) { return event != null; }
}
