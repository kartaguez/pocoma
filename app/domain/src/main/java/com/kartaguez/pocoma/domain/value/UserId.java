package com.kartaguez.pocoma.domain.value;

import java.util.Objects;
import java.util.UUID;

public final class UserId {

	private final UUID value;

	public UserId(UUID value) {
		this.value = Objects.requireNonNull(value, "value must not be null");
	}

	public static UserId of(UUID value) {
		return new UserId(value);
	}

	public UUID value() {
		return value;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof UserId userId)) {
			return false;
		}
		return value.equals(userId.value);
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
