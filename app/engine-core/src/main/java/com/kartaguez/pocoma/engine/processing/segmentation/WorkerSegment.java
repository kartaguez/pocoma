package com.kartaguez.pocoma.engine.processing.segmentation;

import static java.util.Objects.requireNonNull;

/**
 * Transitional technical segment assigned to a worker instance.
 */
public record WorkerSegment(int segmentIndex, int segmentCount) {

	public WorkerSegment {
		if (segmentCount <= 0) {
			throw new IllegalArgumentException("segmentCount must be positive");
		}
		if (segmentIndex < 0 || segmentIndex >= segmentCount) {
			throw new IllegalArgumentException("segmentIndex must be between zero and segmentCount exclusive");
		}
	}

	public static WorkerSegment single() {
		return new WorkerSegment(0, 1);
	}

	public boolean owns(PartitionHash partitionHash) {
		requireNonNull(partitionHash, "partitionHash must not be null");
		return segmentOf(partitionHash) == segmentIndex;
	}

	public int segmentOf(PartitionHash partitionHash) {
		requireNonNull(partitionHash, "partitionHash must not be null");
		return Math.floorMod(partitionHash.value(), segmentCount);
	}
}
