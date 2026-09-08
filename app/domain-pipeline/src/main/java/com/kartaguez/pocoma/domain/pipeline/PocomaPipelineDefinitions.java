package com.kartaguez.pocoma.domain.pipeline;

import java.util.List;

public final class PocomaPipelineDefinitions {
	private static final List<PipelineVersionDefinition> ALL = List.of(
			new PipelineVersionDefinition(
					new PipelineDefinition(PipelineId.of("balance-projection"), 2),
					VersionApplicability.from(1)));

	private PocomaPipelineDefinitions() {}

	public static List<PipelineVersionDefinition> all() {
		return ALL;
	}
}
