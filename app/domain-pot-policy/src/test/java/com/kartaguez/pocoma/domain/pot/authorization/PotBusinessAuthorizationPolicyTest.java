package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.BUSINESS_POLICY_DENIED;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.CONFIGURATION_ERROR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_CREATOR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_MEMBER;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_TARGET_SHAREHOLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PotBusinessAuthorizationPolicyTest {

	private static final Set<PotAction> MEMBER_ACTIONS = EnumSet.of(
			PotAction.VIEW_POT,
			PotAction.VIEW_SHAREHOLDER,
			PotAction.VIEW_EXPENSE,
			PotAction.CREATE_EXPENSE,
			PotAction.UPDATE_EXPENSE_DETAILS,
			PotAction.UPDATE_EXPENSE_SHARES,
			PotAction.DELETE_EXPENSE,
			PotAction.VIEW_BALANCE);

	private final PotBusinessAuthorizationPolicy policy = new PotBusinessAuthorizationPolicy();

	@Test
	void creatorIsAllowedForEveryCanonicalPotAction() {
		for (PotAction action : PotAction.values()) {
			assertTrue(policy.decide(targetType(action), AuthorizationFacts.of(IS_POT_CREATOR), action).isAllowed(),
					action.name());
		}
	}

	@Test
	void memberIsAllowedOnlyByTheCanonicalMemberRules() {
		for (PotAction action : PotAction.values()) {
			AuthorizationDecision decision = policy.decide(
					targetType(action), AuthorizationFacts.of(IS_POT_MEMBER), action);
			assertEquals(MEMBER_ACTIONS.contains(action), decision.isAllowed(), action.name());
			if (!MEMBER_ACTIONS.contains(action)) assertDenied(decision, BUSINESS_POLICY_DENIED);
		}
	}

	@Test
	void targetShareholderFactOnlyAuthorizesUpdatingTheirDetails() {
		for (PotAction action : PotAction.values()) {
			AuthorizationDecision decision = policy.decide(
					targetType(action), AuthorizationFacts.of(IS_TARGET_SHAREHOLDER), action);
			assertEquals(action == PotAction.UPDATE_SHAREHOLDER_DETAILS, decision.isAllowed(), action.name());
		}
	}

	@Test
	void noBusinessFactFailsWithOneNonAmbiguousReason() {
		for (PotAction action : PotAction.values()) {
			assertDenied(policy.decide(targetType(action), AuthorizationFacts.of(), action), BUSINESS_POLICY_DENIED);
		}
	}

	@Test
	void mismatchedTargetTypeFailsClosed() {
		AuthorizationDecision decision = policy.decide(
				AuthorizationTargetType.POT, AuthorizationFacts.of(IS_POT_CREATOR), PotAction.VIEW_EXPENSE);
		assertFalse(decision.isAllowed());
		assertDenied(decision, CONFIGURATION_ERROR);
	}

	private static AuthorizationTargetType targetType(PotAction action) {
		return switch (action) {
			case VIEW_POT, UPDATE_POT_DETAILS, DELETE_POT, VIEW_BALANCE -> AuthorizationTargetType.POT;
			case VIEW_SHAREHOLDER, ADD_SHAREHOLDER, UPDATE_SHAREHOLDER_DETAILS,
					UPDATE_SHAREHOLDER_WEIGHTS, REMOVE_SHAREHOLDER -> AuthorizationTargetType.SHAREHOLDER;
			case VIEW_EXPENSE, CREATE_EXPENSE, UPDATE_EXPENSE_DETAILS,
					UPDATE_EXPENSE_SHARES, DELETE_EXPENSE -> AuthorizationTargetType.EXPENSE;
		};
	}

	private static void assertDenied(AuthorizationDecision decision, AuthorizationDenialReason reason) {
		AuthorizationDecision.Denied denied = assertInstanceOf(AuthorizationDecision.Denied.class, decision);
		assertEquals(reason, denied.reason());
	}
}
