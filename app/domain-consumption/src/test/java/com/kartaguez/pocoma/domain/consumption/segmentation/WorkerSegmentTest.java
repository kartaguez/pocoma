package com.kartaguez.pocoma.domain.consumption.segmentation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkerSegmentTest {

	@Test
	void validatesSegmentBounds() {
		assertDoesNotThrow(() -> new WorkerSegment(0, 1));
		assertDoesNotThrow(() -> new WorkerSegment(2, 3));

		assertThrows(IllegalArgumentException.class, () -> new WorkerSegment(0, 0));
		assertThrows(IllegalArgumentException.class, () -> new WorkerSegment(-1, 3));
		assertThrows(IllegalArgumentException.class, () -> new WorkerSegment(3, 3));
	}

	@Test
	void assignsEveryHashToExactlyOneSegment() {
		for (int hash = -100; hash <= 100; hash++) {
			int owners = 0;
			for (int index = 0; index < 4; index++) {
				if (new WorkerSegment(index, 4).owns(new PartitionHash(hash))) {
					owners++;
				}
			}
			assertEquals(1, owners);
		}
	}

	@Test
	void handlesNegativeHashesDeterministically() {
		WorkerSegment segment = new WorkerSegment(3, 4);

		assertEquals(3, segment.segmentOf(new PartitionHash(-1)));
		assertTrue(segment.owns(new PartitionHash(-1)));
		assertFalse(new WorkerSegment(0, 4).owns(new PartitionHash(-1)));
	}

	@Test
	void singleSegmentOwnsEveryHash() {
		WorkerSegment segment = WorkerSegment.single();

		assertTrue(segment.owns(new PartitionHash(Integer.MIN_VALUE)));
		assertTrue(segment.owns(new PartitionHash(0)));
		assertTrue(segment.owns(new PartitionHash(Integer.MAX_VALUE)));
	}

	@Test
	void rejectsNullHash() {
		WorkerSegment segment = WorkerSegment.single();

		assertThrows(NullPointerException.class, () -> segment.owns(null));
		assertThrows(NullPointerException.class, () -> segment.segmentOf(null));
	}
}
