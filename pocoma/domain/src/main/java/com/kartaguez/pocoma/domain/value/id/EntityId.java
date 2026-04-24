package com.kartaguez.pocoma.domain.value.id;

import java.util.Objects;
import java.util.UUID;

public class EntityId {

	private final UUID value;

	public EntityId(UUID value) {
		this.value = Objects.requireNonNull(value, "value must not be null");
	}

	public static EntityId of(UUID value) {
		return new EntityId(value);
	}

	public UUID value() {
		return value;
	}

	@Override
	public boolean equals(Object object) {
		if (this == object) {
			return true;
		}
		if (object == null || getClass() != object.getClass()) {
			return false;
		}
		EntityId entityId = (EntityId) object;
		return value.equals(entityId.value);
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
