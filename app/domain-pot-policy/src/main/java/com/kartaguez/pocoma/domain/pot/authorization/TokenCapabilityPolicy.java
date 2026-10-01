package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.MISSING_CAPABILITY;
import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;

/** Pure provider-neutral policy requiring every current capability supplied by orchestration. */
public final class TokenCapabilityPolicy {

	public AuthorizationDecision decide(
			TokenCapabilities tokenCapabilities,
			RequiredCurrentCapabilities requiredCapabilities) {
		requireNonNull(tokenCapabilities, "tokenCapabilities must not be null");
		requireNonNull(requiredCapabilities, "requiredCapabilities must not be null");
		return tokenCapabilities.containsAll(requiredCapabilities)
				? AuthorizationDecision.allow()
				: AuthorizationDecision.deny(MISSING_CAPABILITY);
	}
}
