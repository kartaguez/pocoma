package com.kartaguez.pocoma.domain.consumption.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TerminalReasonTest {

	@Test
	void acceptsAnyNonBlankGenericCode() {
		assertEquals("BUSINESS_CONFLICT", new TerminalReason("BUSINESS_CONFLICT").code());
		assertEquals("EVENT_EXECUTION_FAILURE", new TerminalReason("EVENT_EXECUTION_FAILURE").code());
	}

	@Test
	void rejectsMissingOrBlankCodes() {
		assertThrows(NullPointerException.class, () -> new TerminalReason(null));
		assertThrows(IllegalArgumentException.class, () -> new TerminalReason("  "));
	}
}
