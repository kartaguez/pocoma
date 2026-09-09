package com.kartaguez.pocoma.pipeline.pot;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.EventPipelineRelevance;
public final class PotEventPipelineRelevance implements EventPipelineRelevance {
	@Override public PipelineId pipelineId(){return PipelineId.of(PotProjectionPipeline.PIPELINE_ID);}
	@Override public boolean supports(BusinessEvent event){return event!=null;}
}
