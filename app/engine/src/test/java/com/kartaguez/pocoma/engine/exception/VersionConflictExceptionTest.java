package com.kartaguez.pocoma.engine.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VersionConflictExceptionTest {

	@Test
	void createsVersionConflictException() {
		VersionConflictException exception = new VersionConflictException("Pot version has changed");

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
		assertEquals("Pot version has changed", exception.getMessage());
	}

	@Test
	void rejectsNullMessage() {
		assertThrows(NullPointerException.class, () -> new VersionConflictException(null));
	}
}
