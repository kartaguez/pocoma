package com.kartaguez.pocoma.domain.value;

import java.util.Objects;

public final class Name {

	private final String value;

	public Name(String value) {
		this.value = Objects.requireNonNull(value, "value must not be null");
	}

	public static Name of(String value) {
		return new Name(value);
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof Name name)) {
			return false;
		}
		return value.equals(name.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(value);
	}

	@Override
	public String toString() {
		return value;
	}
}
