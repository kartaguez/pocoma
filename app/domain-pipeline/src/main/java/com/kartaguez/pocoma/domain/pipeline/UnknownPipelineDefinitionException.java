package com.kartaguez.pocoma.domain.pipeline;

public final class UnknownPipelineDefinitionException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	public UnknownPipelineDefinitionException(PipelineDefinition identity) {
		super("Unknown pipeline definition: " + identity.pipelineId().value() + "@" + identity.pipelineVersion());
	}
}
