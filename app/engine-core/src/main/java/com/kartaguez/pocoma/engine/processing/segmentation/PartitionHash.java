package com.kartaguez.pocoma.engine.processing.segmentation;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * Transitional technical hash used to assign durable work to a worker segment.
 */
public record PartitionHash(int value) {

	public static PartitionHash forPot(UUID potId) {
		requireNonNull(potId, "potId must not be null");
		return new PartitionHash(potId.hashCode());
	}

	public static PartitionHash forPipelinePot(String pipelineId, UUID potId) {
		requireNonNull(pipelineId, "pipelineId must not be null");
		if (pipelineId.isBlank()) {
			throw new IllegalArgumentException("pipelineId must not be blank");
		}
		requireNonNull(potId, "potId must not be null");
		return new PartitionHash(31 * pipelineId.hashCode() + potId.hashCode());
	}
}
