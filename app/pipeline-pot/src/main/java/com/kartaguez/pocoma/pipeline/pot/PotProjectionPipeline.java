package com.kartaguez.pocoma.pipeline.pot;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.model.ProjectionProducerBinding;

public final class PotProjectionPipeline {

	public static final String PIPELINE_ID = "read-pot";
	public static final String TASK_TYPE = "READ_POT";
	public static final String PROJECTION_TYPE = "READ_POT";
	public static final ProjectionType TYPE = new ProjectionType(PROJECTION_TYPE);

	private PotProjectionPipeline() {
	}

	public static PipelineDefinition definition(int version) {
		return new PipelineDefinition(PipelineId.of(PIPELINE_ID), version);
	}

	public static ProjectionProducerBinding producerBinding(PipelineDefinition pipeline) {
		if (!PIPELINE_ID.equals(pipeline.pipelineId().value())) {
			throw new IllegalArgumentException("Not a read-pot pipeline: " + pipeline);
		}
		return new ProjectionProducerBinding(TYPE, pipeline);
	}
}
