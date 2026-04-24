package com.kartaguez.pocoma.engine.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionedTest {

	@Test
	void detectsActiveVersionWithoutEnd() {
		Versioned<String> versioned = new Versioned<>("value", 2, null);

		assertFalse(versioned.isActiveAt(1));
		assertTrue(versioned.isActiveAt(2));
		assertTrue(versioned.isActiveAt(3));
	}

	@Test
	void detectsActiveVersionWithEnd() {
		Versioned<String> versioned = new Versioned<>("value", 2, 4L);

		assertFalse(versioned.isActiveAt(1));
		assertTrue(versioned.isActiveAt(2));
		assertTrue(versioned.isActiveAt(3));
		assertFalse(versioned.isActiveAt(4));
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Versioned<>(null, 1, null));
	}

	@Test
	void rejectsStartedAtVersionLowerThanOne() {
		assertThrows(IllegalArgumentException.class, () -> new Versioned<>("value", 0, null));
	}

	@Test
	void rejectsEndedAtVersionLowerThanOrEqualToStartedAtVersion() {
		assertThrows(IllegalArgumentException.class, () -> new Versioned<>("value", 2, 2L));
	}
}
