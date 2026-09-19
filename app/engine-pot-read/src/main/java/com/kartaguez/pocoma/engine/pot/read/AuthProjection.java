package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

record AuthProjection(UserId creatorId, Map<UserId, ShareholderId> shareholderIdsByUserId) {
	AuthProjection {
		requireNonNull(creatorId, "creatorId must not be null");
		requireNonNull(shareholderIdsByUserId, "shareholderIdsByUserId must not be null");
		shareholderIdsByUserId = Map.copyOf(shareholderIdsByUserId);
	}

	boolean isCreator(UserId userId) {
		return creatorId.equals(requireNonNull(userId, "userId must not be null"));
	}

	Optional<ShareholderId> shareholderIdFor(UserId userId) {
		return Optional.ofNullable(shareholderIdsByUserId.get(requireNonNull(userId, "userId must not be null")));
	}
}
