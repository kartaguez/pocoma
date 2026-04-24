package com.kartaguez.pocoma.domain.factory;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.created.PotCreated;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

public final class PotFactory {

	private PotFactory() {
	}

	public static PotCreated createPot(Label label, UserId creatorId) {
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");

		return new PotCreated(PotId.of(UUID.randomUUID()), label, creatorId);
	}
}
