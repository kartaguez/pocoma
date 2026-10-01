package com.kartaguez.pocoma.engine.processing.segmentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PartitionHashTest {

	private static final UUID POT_ID = UUID.fromString("00000000-0000-0000-0000-000000000123");

	@Test
	void computesAStablePotHash() {
		assertEquals(PartitionHash.forPot(POT_ID), PartitionHash.forPot(POT_ID));
		assertEquals(POT_ID.hashCode(), PartitionHash.forPot(POT_ID).value());
	}

	@Test
	void computesAStablePipelineAndPotHash() {
		PartitionHash first = PartitionHash.forPipelinePot("balances", POT_ID);
		PartitionHash second = PartitionHash.forPipelinePot("balances", POT_ID);

		assertEquals(first, second);
		assertNotEquals(first, PartitionHash.forPipelinePot("settlements", POT_ID));
	}

	@Test
	void rejectsInvalidPartitionKeys() {
		assertThrows(NullPointerException.class, () -> PartitionHash.forPot(null));
		assertThrows(NullPointerException.class, () -> PartitionHash.forPipelinePot(null, POT_ID));
		assertThrows(IllegalArgumentException.class, () -> PartitionHash.forPipelinePot(" ", POT_ID));
		assertThrows(NullPointerException.class, () -> PartitionHash.forPipelinePot("balances", null));
	}
}
