package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProcessingFailureTest {

	@Test
	void describesAProcessingFailure() {
		Instant occurredAt = Instant.parse("2026-08-27T10:00:00Z");
		ProcessingFailure failure = new ProcessingFailure(
				new ProcessingFailureCode("DEADLOCK"), "TRANSIENT", "Version mismatch", occurredAt);

		assertEquals(new ProcessingFailureCode("DEADLOCK"), failure.code());
		assertEquals("TRANSIENT", failure.category());
		assertEquals("Version mismatch", failure.message());
		assertEquals(occurredAt, failure.occurredAt());
	}

	@Test
	void rejectsIncompleteFailureDescriptions() {
		Instant now = Instant.now();

		var code = new ProcessingFailureCode("CODE");
		assertThrows(NullPointerException.class, () -> new ProcessingFailure(null, "category", "message", now));
		assertThrows(NullPointerException.class, () -> new ProcessingFailure(code, null, "message", now));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(code, " ", "message", now));
		assertThrows(NullPointerException.class, () -> new ProcessingFailure(code, "category", null, now));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingFailure(code, "category", " ", now));
		assertThrows(NullPointerException.class, () -> new ProcessingFailure(code, "category", "message", null));
	}
}
