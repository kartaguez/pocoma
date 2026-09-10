package com.kartaguez.pocoma.engine.read.projection;

import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

public record SelectedPotPipelineRange(
		int pipelineVersion,
		long fromVersionInclusive,
		OptionalLong toVersionExclusive) {

	public SelectedPotPipelineRange {
		requireNonNull(toVersionExclusive, "toVersionExclusive must not be null");
		if (pipelineVersion < 1 || fromVersionInclusive < 1) {
			throw new IllegalArgumentException("pipeline and source versions must be positive");
		}
		toVersionExclusive.ifPresent(to -> {
			if (to <= fromVersionInclusive) {
				throw new IllegalArgumentException("range end must be greater than its start");
			}
		});
	}
}
