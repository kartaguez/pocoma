package com.kartaguez.pocoma.domain.pot.policy;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.MISSING_CAPABILITY;
import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDecision;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFacts;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.authorization.PotBusinessAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.authorization.TokenCapabilityPolicy;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

final class LegacyReadAuthorizationSupport {

	private static final TokenCapabilityPolicy TOKEN_POLICY = new TokenCapabilityPolicy();
	private static final PotBusinessAuthorizationPolicy BUSINESS_POLICY = new PotBusinessAuthorizationPolicy();

	private LegacyReadAuthorizationSupport() {
	}

	static void assertPotScoped(
			UserId userId,
			Set<Permission> permissions,
			UserId creatorId,
			Set<UserId> shareholderUserIds,
			AuthorizationTargetType targetType,
			PotAction action,
			Permission capability,
			String businessDenialMessage,
			String missingCapabilityMessage) {
		assertAuthenticated(userId);
		requireNonNull(creatorId, "creatorId must not be null");
		requireNonNull(shareholderUserIds, "shareholderUserIds must not be null");
		assertCapability(permissions, capability, missingCapabilityMessage);

		EnumSet<AuthorizationFact> facts = EnumSet.noneOf(AuthorizationFact.class);
		if (creatorId.equals(userId)) facts.add(AuthorizationFact.IS_POT_CREATOR);
		if (shareholderUserIds.contains(userId)) facts.add(AuthorizationFact.IS_POT_MEMBER);
		AuthorizationDecision decision = BUSINESS_POLICY.decide(targetType, new AuthorizationFacts(facts), action);
		if (!decision.isAllowed()) {
			throw new BusinessRuleViolationException("POT_READ_FORBIDDEN", businessDenialMessage);
		}
	}

	static void assertCapability(
			Set<Permission> permissions,
			Permission capability,
			String missingCapabilityMessage) {
		requireNonNull(permissions, "permissions must not be null");
		AuthorizationDecision decision = TOKEN_POLICY.decide(
				new TokenCapabilities(permissions), RequiredCurrentCapabilities.of(capability));
		if (decision instanceof AuthorizationDecision.Denied denied && denied.reason() == MISSING_CAPABILITY) {
			throw new BusinessRuleViolationException("MISSING_PERMISSION", missingCapabilityMessage);
		}
	}

	static void assertAuthenticated(UserId userId) {
		if (userId == null) {
			throw new BusinessRuleViolationException("ANONYMOUS_USER", "Anonymous users cannot read the pot");
		}
	}
}
