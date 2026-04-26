package com.kartaguez.pocoma.domain.created;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotCreated(PotId id, Label label, UserId creatorId) {

	public PotCreated {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(label, "label must not be null");
		Objects.requireNonNull(creatorId, "creatorId must not be null");
	}
}
