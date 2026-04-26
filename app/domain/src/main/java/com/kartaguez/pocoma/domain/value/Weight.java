package com.kartaguez.pocoma.domain.value;

import java.util.Objects;

public final class Weight {

	private final Fraction value;

	public Weight(Fraction value) {
		this.value = Objects.requireNonNull(value, "value must not be null");
		if (this.value.compareTo(Fraction.ZERO) < 0) {
			throw new IllegalArgumentException("value must not be negative");
		}
	}

	public static Weight of(Fraction value) {
		return new Weight(value);
	}

	public Fraction value() {
		return value;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof Weight weight)) {
			return false;
		}
		return value.equals(weight.value);
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
