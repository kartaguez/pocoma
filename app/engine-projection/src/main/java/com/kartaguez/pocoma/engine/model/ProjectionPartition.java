package com.kartaguez.pocoma.engine.model;

public record ProjectionPartition(int segmentIndex, int segmentCount) {

	public ProjectionPartition {
		if (segmentCount < 1) {
			throw new IllegalArgumentException("segmentCount must be greater than or equal to 1");
		}
		if (segmentIndex < 0) {
			throw new IllegalArgumentException("segmentIndex must be greater than or equal to 0");
		}
		if (segmentIndex >= segmentCount) {
			throw new IllegalArgumentException("segmentIndex must be lower than segmentCount");
		}
	}

	public static ProjectionPartition single() {
		return new ProjectionPartition(0, 1);
	}
}
