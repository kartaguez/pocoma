package com.kartaguez.pocoma.engine.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProjectionPartitionTest {

	@Test
	void validatesSegmentBounds() {
		assertDoesNotThrow(() -> new ProjectionPartition(0, 1));
		assertDoesNotThrow(() -> new ProjectionPartition(2, 3));

		assertThrows(IllegalArgumentException.class, () -> new ProjectionPartition(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionPartition(-1, 3));
		assertThrows(IllegalArgumentException.class, () -> new ProjectionPartition(3, 3));
	}

	@Test
	void singlePartitionOwnsEveryHash() {
		ProjectionPartition partition = ProjectionPartition.single();

		assertEquals(0, partition.segmentIndex());
		assertEquals(1, partition.segmentCount());
		assertEquals(0, PotPartitioner.segmentOf(12345, partition.segmentCount()));
	}
}
