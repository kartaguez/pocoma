package com.kartaguez.pocoma.pipeline.balance;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ProjectionProducerBinding;

public final class BalancePipeline {
	public static final String PIPELINE_ID = "balance-projection";
	public static final String TASK_TYPE = "COMPUTE_BALANCES_FOR_VERSION";
	public static final String PROJECTION_TYPE = "POT_BALANCES";
	public static final ProjectionType TYPE = new ProjectionType(PROJECTION_TYPE);
	private BalancePipeline() {}
	public static PipelineDefinition definition(int version) {
		return new PipelineDefinition(PipelineId.of(PIPELINE_ID), version);
	}
	public static ProjectionProducerBinding producerBinding(PipelineDefinition pipeline) {
		if (!PIPELINE_ID.equals(pipeline.pipelineId().value())) {
			throw new IllegalArgumentException("Not a balance pipeline: " + pipeline);
		}
		return new ProjectionProducerBinding(TYPE, pipeline);
	}
}
