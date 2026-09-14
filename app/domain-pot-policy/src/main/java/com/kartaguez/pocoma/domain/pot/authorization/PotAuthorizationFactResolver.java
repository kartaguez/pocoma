package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_CREATOR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_MEMBER;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_TARGET_SHAREHOLDER;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;

import com.kartaguez.pocoma.domain.pot.value.UserId;

/** Pure derivation of ephemeral authorization facts from Pot identity relations. */
public final class PotAuthorizationFactResolver {

	public AuthorizationFacts resolve(
			PotAuthorizationRelations relations,
			UserId currentUserId,
			AuthorizationTarget target) {
		requireNonNull(relations, "relations must not be null");
		requireNonNull(currentUserId, "currentUserId must not be null");
		requireNonNull(target, "target must not be null");

		if (target instanceof AuthorizationTarget.ExistingPot potTarget
				&& !relations.potId().equals(potTarget.targetId())) {
			throw new IllegalArgumentException("target Pot must match authorization relations");
		}

		EnumSet<AuthorizationFact> facts = EnumSet.noneOf(AuthorizationFact.class);
		if (relations.creatorUserId().equals(currentUserId)) facts.add(IS_POT_CREATOR);
		if (relations.activeShareholders().containsValue(currentUserId)) facts.add(IS_POT_MEMBER);
		if (target instanceof AuthorizationTarget.ExistingShareholder shareholderTarget
				&& currentUserId.equals(relations.activeShareholders().get(shareholderTarget.targetId()))) {
			facts.add(IS_TARGET_SHAREHOLDER);
		}
		return new AuthorizationFacts(facts);
	}
}
