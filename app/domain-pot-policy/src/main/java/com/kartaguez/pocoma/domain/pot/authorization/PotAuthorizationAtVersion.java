package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Logical, non-persistent facts resolved for one user and target at a business version. */
public record PotAuthorizationAtVersion(
		PotId potId,
		long businessVersion,
		UserId userId,
		AuthorizationTarget target,
		AuthorizationFacts facts) {

	public PotAuthorizationAtVersion {
		requireNonNull(potId, "potId must not be null");
		if (businessVersion < 1) throw new IllegalArgumentException("businessVersion must be positive");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(target, "target must not be null");
		requireNonNull(facts, "facts must not be null");
	}
}
