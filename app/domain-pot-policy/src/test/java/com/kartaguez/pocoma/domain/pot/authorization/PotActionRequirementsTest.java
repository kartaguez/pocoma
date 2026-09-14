package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.BALANCE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_CREATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_DELETE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_DELETE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.POT_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_CREATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_DELETE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_UPDATE;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.SHAREHOLDER_VIEW;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetContract.EXISTING_ONLY;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetContract.PROSPECTIVE_ALLOWED;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType.EXPENSE;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType.POT;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTargetType.SHAREHOLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PotActionRequirementsTest {

	@Test
	void exposesTheExactExhaustiveCanonicalMapping() {
		Map<PotAction, PotActionRequirement> expected = Map.ofEntries(
				entry(PotAction.VIEW_POT, new PotActionRequirement(POT, POT_VIEW, EXISTING_ONLY)),
				entry(PotAction.UPDATE_POT_DETAILS, new PotActionRequirement(POT, POT_UPDATE, EXISTING_ONLY)),
				entry(PotAction.DELETE_POT, new PotActionRequirement(POT, POT_DELETE, EXISTING_ONLY)),
				entry(PotAction.VIEW_SHAREHOLDER, new PotActionRequirement(SHAREHOLDER, SHAREHOLDER_VIEW, EXISTING_ONLY)),
				entry(PotAction.ADD_SHAREHOLDER, new PotActionRequirement(SHAREHOLDER, SHAREHOLDER_CREATE, PROSPECTIVE_ALLOWED)),
				entry(PotAction.UPDATE_SHAREHOLDER_DETAILS, new PotActionRequirement(SHAREHOLDER, SHAREHOLDER_UPDATE, EXISTING_ONLY)),
				entry(PotAction.UPDATE_SHAREHOLDER_WEIGHTS, new PotActionRequirement(SHAREHOLDER, SHAREHOLDER_UPDATE, EXISTING_ONLY)),
				entry(PotAction.REMOVE_SHAREHOLDER, new PotActionRequirement(SHAREHOLDER, SHAREHOLDER_DELETE, EXISTING_ONLY)),
				entry(PotAction.VIEW_EXPENSE, new PotActionRequirement(EXPENSE, EXPENSE_VIEW, EXISTING_ONLY)),
				entry(PotAction.CREATE_EXPENSE, new PotActionRequirement(EXPENSE, EXPENSE_CREATE, PROSPECTIVE_ALLOWED)),
				entry(PotAction.UPDATE_EXPENSE_DETAILS, new PotActionRequirement(EXPENSE, EXPENSE_UPDATE, EXISTING_ONLY)),
				entry(PotAction.UPDATE_EXPENSE_SHARES, new PotActionRequirement(EXPENSE, EXPENSE_UPDATE, EXISTING_ONLY)),
				entry(PotAction.DELETE_EXPENSE, new PotActionRequirement(EXPENSE, EXPENSE_DELETE, EXISTING_ONLY)),
				entry(PotAction.VIEW_BALANCE, new PotActionRequirement(POT, BALANCE_VIEW, EXISTING_ONLY)));
		PotActionRequirements actual = new PotActionRequirements();

		assertEquals(PotAction.values().length, expected.size());
		expected.forEach((action, requirement) -> assertEquals(requirement, actual.find(action).orElseThrow(), action.name()));
	}

	private static Map.Entry<PotAction, PotActionRequirement> entry(
			PotAction action, PotActionRequirement requirement) {
		return Map.entry(action, requirement);
	}
}
