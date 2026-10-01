package com.kartaguez.pocoma.engine.projection.pot;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record AuthProjectionInput(
		PotId potId,
		long version,
		UserId creatorUserId,
		Map<ShareholderId, UserId> activeShareholders) {

	public AuthProjectionInput {
		requireNonNull(potId, "potId must not be null");
		if (version < 1) throw new IllegalArgumentException("version must be positive");
		requireNonNull(creatorUserId, "creatorUserId must not be null");
		activeShareholders = Map.copyOf(requireNonNull(activeShareholders,
				"activeShareholders must not be null"));
	}
}
