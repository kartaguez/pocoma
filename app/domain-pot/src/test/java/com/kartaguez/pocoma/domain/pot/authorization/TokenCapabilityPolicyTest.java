package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.VIEW_ARCHIVE;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.MISSING_CAPABILITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;

class TokenCapabilityPolicyTest {

	private final TokenCapabilityPolicy policy = new TokenCapabilityPolicy();

	@Test
	void allowsOnlyWhenEveryRequiredCurrentCapabilityIsPresent() {
		assertTrue(policy.decide(
				TokenCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE),
				RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE)).isAllowed());

		AuthorizationDecision decision = policy.decide(
				TokenCapabilities.of(EXPENSE_VIEW),
				RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE));
		AuthorizationDecision.Denied denied = assertInstanceOf(AuthorizationDecision.Denied.class, decision);
		assertEquals(MISSING_CAPABILITY, denied.reason());
	}

	@Test
	void ignoresOnlyUnrequiredTokenCapabilities() {
		assertTrue(policy.decide(
				TokenCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE),
				RequiredCurrentCapabilities.of(EXPENSE_VIEW)).isAllowed());
	}
}
