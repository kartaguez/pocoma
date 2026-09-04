package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProcessingFailureCodeTest {

	@Test
	void acceptsExtensibleTechnicalCodes() {
		assertEquals("DEADLOCK", new ProcessingFailureCode("DEADLOCK").value());
		assertEquals("DATABASE_UNAVAILABLE", new ProcessingFailureCode("DATABASE_UNAVAILABLE").value());
	}

	@Test
	void rejectsMissingCodes() {
		assertThrows(NullPointerException.class, () -> new ProcessingFailureCode(null));
		assertThrows(IllegalArgumentException.class, () -> new ProcessingFailureCode("  "));
	}
}
