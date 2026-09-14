package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

import java.util.Map;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

/** Small current or historical relation model from which authorization facts are derived. */
public record PotAuthorizationRelations(
		PotId potId,
		UserId creatorUserId,
		Map<ShareholderId, UserId> activeShareholders) {

	public PotAuthorizationRelations {
		requireNonNull(potId, "potId must not be null");
		requireNonNull(creatorUserId, "creatorUserId must not be null");
		activeShareholders = Map.copyOf(requireNonNull(activeShareholders, "activeShareholders must not be null"));
	}
}
