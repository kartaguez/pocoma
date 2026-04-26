package com.kartaguez.pocoma.domain.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AmountTest {

	@Test
	void createsAmountFromFraction() {
		Fraction value = new Fraction(3, 4);

		Amount amount = new Amount(value);

		assertEquals(value, amount.value());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Amount(null));
	}

	@Test
	void rejectsNegativeValue() {
		assertThrows(IllegalArgumentException.class, () -> new Amount(new Fraction(-1, 2)));
	}

	@Test
	void addsAmounts() {
		Amount first = new Amount(new Fraction(1, 2));
		Amount second = new Amount(new Fraction(1, 3));

		Amount result = first.add(second);

		assertEquals(new Amount(new Fraction(5, 6)), result);
	}

	@Test
	void subtractsAmounts() {
		Amount first = new Amount(new Fraction(3, 4));
		Amount second = new Amount(new Fraction(1, 6));

		Amount result = first.subtract(second);

		assertEquals(new Amount(new Fraction(7, 12)), result);
	}

	@Test
	void rejectsNegativeSubtractionResult() {
		Amount first = new Amount(new Fraction(1, 6));
		Amount second = new Amount(new Fraction(3, 4));

		assertThrows(IllegalArgumentException.class, () -> first.subtract(second));
	}

	@Test
	void exposesZeroAmount() {
		assertEquals(new Amount(new Fraction(0, 42)), Amount.ZERO);
	}
}
