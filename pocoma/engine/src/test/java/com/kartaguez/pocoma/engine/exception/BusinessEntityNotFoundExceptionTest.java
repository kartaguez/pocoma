package com.kartaguez.pocoma.engine.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BusinessEntityNotFoundExceptionTest {

	@Test
	void createsBusinessEntityNotFoundException() {
		BusinessEntityNotFoundException exception =
				new BusinessEntityNotFoundException("POT_NOT_FOUND", "Pot was not found");

		assertEquals("POT_NOT_FOUND", exception.entityCode());
		assertEquals("Pot was not found", exception.getMessage());
	}

	@Test
	void rejectsNullEntityCode() {
		assertThrows(NullPointerException.class, () -> new BusinessEntityNotFoundException(null, "Pot was not found"));
	}

	@Test
	void rejectsNullMessage() {
		assertThrows(NullPointerException.class, () -> new BusinessEntityNotFoundException("POT_NOT_FOUND", null));
	}
}
