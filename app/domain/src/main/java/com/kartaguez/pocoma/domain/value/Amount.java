package com.kartaguez.pocoma.domain.value;

import java.util.Objects;

public final class Amount {

	public static final Amount ZERO = new Amount(Fraction.ZERO);

	private final Fraction value;

	public Amount(Fraction value) {
		this.value = Objects.requireNonNull(value, "value must not be null");
		if (this.value.compareTo(Fraction.ZERO) < 0) {
			throw new IllegalArgumentException("value must not be negative");
		}
	}

	public static Amount of(Fraction value) {
		return new Amount(value);
	}

	public Fraction value() {
		return value;
	}

	public Amount add(Amount other) {
		Objects.requireNonNull(other, "other must not be null");

		return new Amount(value.add(other.value));
	}

	public Amount subtract(Amount other) {
		Objects.requireNonNull(other, "other must not be null");

		return new Amount(value.subtract(other.value));
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof Amount amount)) {
			return false;
		}
		return value.equals(amount.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
