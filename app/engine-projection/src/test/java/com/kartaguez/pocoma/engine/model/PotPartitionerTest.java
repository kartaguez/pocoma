package com.kartaguez.pocoma.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotPartitionerTest {

	@Test
	void computesStablePositiveHashFromPotId() {
		PotId potId = PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000123"));

		int first = PotPartitioner.partitionHash(potId);
		int second = PotPartitioner.partitionHash(potId);

		assertEquals(first, second);
		assertEquals(potId.value().hashCode() & Integer.MAX_VALUE, first);
	}

	@Test
	void computesStableSegmentFromHash() {
		int hash = 42;

		assertEquals(2, PotPartitioner.segmentOf(hash, 5));
		assertEquals(2, PotPartitioner.segmentOf(hash, 10));
		assertEquals(0, PotPartitioner.segmentOf(hash, 1));
	}
}
