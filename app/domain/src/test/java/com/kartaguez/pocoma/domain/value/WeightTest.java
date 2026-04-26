package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WeightTest {

	@Test
	void createsWeightFromFraction() {
		Fraction value = new Fraction(3, 4);

		Weight weight = new Weight(value);

		assertEquals(value, weight.value());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Weight(null));
	}

	@Test
	void rejectsNegativeValue() {
		assertThrows(IllegalArgumentException.class, () -> new Weight(new Fraction(-1, 2)));
	}

	@Test
	void acceptsZeroValue() {
		Weight weight = new Weight(new Fraction(0, 42));

		assertEquals(Fraction.ZERO, weight.value());
	}

	@Test
	void comparesWeightsByValue() {
		assertEquals(new Weight(new Fraction(2, 4)), new Weight(new Fraction(1, 2)));
	}
}
