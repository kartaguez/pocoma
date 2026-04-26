package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LabelTest {

	@Test
	void createsLabelFromString() {
		Label label = new Label("fresh");

		assertEquals("fresh", label.value());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Label(null));
	}

	@Test
	void comparesLabelsByValue() {
		assertEquals(new Label("fresh"), Label.of("fresh"));
		assertNotEquals(new Label("fresh"), new Label("dry"));
	}
}
