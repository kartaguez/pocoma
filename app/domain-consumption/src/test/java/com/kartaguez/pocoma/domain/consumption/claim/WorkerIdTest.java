package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WorkerIdTest {

	@Test
	void wrapsAWorkerIdentifier() {
		assertEquals("command-worker-1", new WorkerId("command-worker-1").value());
	}

	@Test
	void rejectsNullOrBlankValue() {
		assertThrows(NullPointerException.class, () -> new WorkerId(null));
		assertThrows(IllegalArgumentException.class, () -> new WorkerId(" "));
	}
}
