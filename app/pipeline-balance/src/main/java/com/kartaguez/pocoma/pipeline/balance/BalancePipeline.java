package com.kartaguez.pocoma.pipeline.balance;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;

public final class BalancePipeline {
	public static final String PIPELINE_ID = "balance-projection";
	public static final String TASK_TYPE = "COMPUTE_BALANCES_FOR_VERSION";
	public static final String PROJECTION_TYPE = "POT_BALANCES";
	private BalancePipeline() {}
	public static PipelineDefinition definition(int version) {
		return new PipelineDefinition(PipelineId.of(PIPELINE_ID), version);
	}
}
