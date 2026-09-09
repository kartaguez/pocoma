package com.kartaguez.pocoma.engine.port.in.taskcreation.strategy;

import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Stable relevance of a business Event to a semantic pipeline family. */
public interface EventPipelineRelevance {

	PipelineId pipelineId();

	boolean supports(BusinessEvent event);
}
