package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.BUSINESS_POLICY_DENIED;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.CONFIGURATION_ERROR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_CREATOR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_MEMBER;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_TARGET_SHAREHOLDER;
import static java.util.Objects.requireNonNull;

/** Single pure Pot business authorization matrix shared by write and read paths. */
public final class PotBusinessAuthorizationPolicy {

	public AuthorizationDecision decide(
			AuthorizationTargetType targetType,
			AuthorizationFacts facts,
			PotAction action) {
		requireNonNull(targetType, "targetType must not be null");
		requireNonNull(facts, "facts must not be null");
		requireNonNull(action, "action must not be null");

		if (!supports(targetType, action)) return AuthorizationDecision.deny(CONFIGURATION_ERROR);

		boolean creator = facts.contains(IS_POT_CREATOR);
		boolean member = facts.contains(IS_POT_MEMBER);
		boolean targetShareholder = facts.contains(IS_TARGET_SHAREHOLDER);
		boolean allowed = switch (action) {
			case VIEW_POT, VIEW_BALANCE, VIEW_SHAREHOLDER,
					VIEW_EXPENSE, CREATE_EXPENSE, UPDATE_EXPENSE_DETAILS,
					UPDATE_EXPENSE_SHARES, DELETE_EXPENSE -> creator || member;
			case UPDATE_POT_DETAILS, DELETE_POT, ADD_SHAREHOLDER,
					UPDATE_SHAREHOLDER_WEIGHTS, REMOVE_SHAREHOLDER -> creator;
			case UPDATE_SHAREHOLDER_DETAILS -> creator || targetShareholder;
		};
		return allowed ? AuthorizationDecision.allow() : AuthorizationDecision.deny(BUSINESS_POLICY_DENIED);
	}

	private static boolean supports(AuthorizationTargetType targetType, PotAction action) {
		return switch (action) {
			case VIEW_POT, UPDATE_POT_DETAILS, DELETE_POT, VIEW_BALANCE -> targetType == AuthorizationTargetType.POT;
			case VIEW_SHAREHOLDER, ADD_SHAREHOLDER, UPDATE_SHAREHOLDER_DETAILS,
					UPDATE_SHAREHOLDER_WEIGHTS, REMOVE_SHAREHOLDER -> targetType == AuthorizationTargetType.SHAREHOLDER;
			case VIEW_EXPENSE, CREATE_EXPENSE, UPDATE_EXPENSE_DETAILS,
					UPDATE_EXPENSE_SHARES, DELETE_EXPENSE -> targetType == AuthorizationTargetType.EXPENSE;
		};
	}
}
