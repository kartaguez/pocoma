package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NameTest {

	@Test
	void createsNameFromString() {
		Name name = new Name("ingredient");

		assertEquals("ingredient", name.value());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Name(null));
	}

	@Test
	void comparesNamesByValue() {
		assertEquals(new Name("ingredient"), Name.of("ingredient"));
		assertNotEquals(new Name("ingredient"), new Name("recipe"));
	}
}
