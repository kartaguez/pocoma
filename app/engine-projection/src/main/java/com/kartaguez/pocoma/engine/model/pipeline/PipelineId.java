package com.kartaguez.pocoma.engine.model.pipeline;

import java.util.Objects;

public record PipelineId(String value) {

	public PipelineId {
		Objects.requireNonNull(value, "value must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException("value must not be blank");
		}
	}

	public static PipelineId of(String value) {
		return new PipelineId(value);
	}
}
