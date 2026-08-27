package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProcessingFailureTest {

	@Test
	void describesAProcessingFailure() {
		Instant occurredAt = Instant.parse("2026-08-27T10:00:00Z");
		ProcessingFailure failure = new ProcessingFailure("OPTIMISTIC_CONFLICT", "Version mismatch", occurredAt);

		assertEquals("OPTIMISTIC_CONFLICT", failure.category());
		assertEquals("Version mismatch", failure.message());
		assertEquals(occurredAt, failure.occurredAt());
	}

	@Test
	void rejectsIncompleteFailureDescriptions() {
		Instant now = Instant.now();

		assertThrows(NullPointerException.class, () -> new ProcessingFailure(null, "message", now));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(" ", "message", now));
		assertThrows(NullPointerException.class, () -> new ProcessingFailure("category", null, now));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure("category", " ", now));
		assertThrows(NullPointerException.class, () -> new ProcessingFailure("category", "message", null));
	}
}
