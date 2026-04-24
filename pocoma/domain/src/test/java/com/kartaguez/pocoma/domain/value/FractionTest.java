package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FractionTest {

	@Test
	void normalizesFraction() {
		Fraction fraction = new Fraction(2, -4);

		assertEquals(-1, fraction.numerator());
		assertEquals(2, fraction.denominator());
		assertEquals(new Fraction(-1, 2), fraction);
	}

	@Test
	void normalizesZero() {
		Fraction fraction = new Fraction(0, -42);

		assertEquals(Fraction.ZERO, fraction);
		assertEquals(1, fraction.denominator());
	}

	@Test
	void rejectsZeroDenominator() {
		assertThrows(IllegalArgumentException.class, () -> new Fraction(1, 0));
	}

	@Test
	void addsFractions() {
		Fraction result = new Fraction(1, 2).add(new Fraction(1, 3));

		assertEquals(new Fraction(5, 6), result);
	}

	@Test
	void subtractsFractions() {
		Fraction result = new Fraction(3, 4).subtract(new Fraction(1, 6));

		assertEquals(new Fraction(7, 12), result);
	}

	@Test
	void multipliesFractions() {
		Fraction result = new Fraction(2, 3).multiply(new Fraction(9, 10));

		assertEquals(new Fraction(3, 5), result);
	}

	@Test
	void dividesFractions() {
		Fraction result = new Fraction(2, 3).divide(new Fraction(4, 5));

		assertEquals(new Fraction(5, 6), result);
	}

	@Test
	void rejectsDivisionByZeroFraction() {
		assertThrows(ArithmeticException.class, () -> new Fraction(1, 2).divide(Fraction.ZERO));
	}

	@Test
	void comparesFractions() {
		assertTrue(new Fraction(1, 3).compareTo(new Fraction(1, 2)) < 0);
		assertEquals(0, new Fraction(2, 4).compareTo(new Fraction(1, 2)));
		assertTrue(new Fraction(3, 4).compareTo(new Fraction(2, 3)) > 0);
	}

	@Test
	void rejectsOverflowingResult() {
		Fraction fraction = new Fraction(Long.MAX_VALUE, 1);

		assertThrows(ArithmeticException.class, () -> fraction.add(Fraction.ONE));
	}
}
