package com.kartaguez.pocoma.domain.value.id;

import java.util.UUID;

public final class PotId extends EntityId {

	public PotId(UUID value) {
		super(value);
	}

	public static PotId of(UUID value) {
		return new PotId(value);
	}
}
