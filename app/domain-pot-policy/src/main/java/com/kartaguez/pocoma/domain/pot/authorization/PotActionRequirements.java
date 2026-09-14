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

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Single canonical mapping from every Pot action to its authorization requirements. */
public final class PotActionRequirements {

	private final Map<PotAction, PotActionRequirement> requirements;

	public PotActionRequirements() {
		this(canonicalRequirements());
	}

	public PotActionRequirements(Map<PotAction, PotActionRequirement> requirements) {
		this.requirements = Map.copyOf(requireNonNull(requirements, "requirements must not be null"));
	}

	public Optional<PotActionRequirement> find(PotAction action) {
		return Optional.ofNullable(requirements.get(action));
	}

	private static Map<PotAction, PotActionRequirement> canonicalRequirements() {
		Map<PotAction, PotActionRequirement> values = new EnumMap<>(PotAction.class);
		values.put(PotAction.VIEW_POT, requirement(POT, POT_VIEW, EXISTING_ONLY));
		values.put(PotAction.UPDATE_POT_DETAILS, requirement(POT, POT_UPDATE, EXISTING_ONLY));
		values.put(PotAction.DELETE_POT, requirement(POT, POT_DELETE, EXISTING_ONLY));
		values.put(PotAction.VIEW_SHAREHOLDER, requirement(SHAREHOLDER, SHAREHOLDER_VIEW, EXISTING_ONLY));
		values.put(PotAction.ADD_SHAREHOLDER, requirement(SHAREHOLDER, SHAREHOLDER_CREATE, PROSPECTIVE_ALLOWED));
		values.put(PotAction.UPDATE_SHAREHOLDER_DETAILS, requirement(SHAREHOLDER, SHAREHOLDER_UPDATE, EXISTING_ONLY));
		values.put(PotAction.UPDATE_SHAREHOLDER_WEIGHTS, requirement(SHAREHOLDER, SHAREHOLDER_UPDATE, EXISTING_ONLY));
		values.put(PotAction.REMOVE_SHAREHOLDER, requirement(SHAREHOLDER, SHAREHOLDER_DELETE, EXISTING_ONLY));
		values.put(PotAction.VIEW_EXPENSE, requirement(EXPENSE, EXPENSE_VIEW, EXISTING_ONLY));
		values.put(PotAction.CREATE_EXPENSE, requirement(EXPENSE, EXPENSE_CREATE, PROSPECTIVE_ALLOWED));
		values.put(PotAction.UPDATE_EXPENSE_DETAILS, requirement(EXPENSE, EXPENSE_UPDATE, EXISTING_ONLY));
		values.put(PotAction.UPDATE_EXPENSE_SHARES, requirement(EXPENSE, EXPENSE_UPDATE, EXISTING_ONLY));
		values.put(PotAction.DELETE_EXPENSE, requirement(EXPENSE, EXPENSE_DELETE, EXISTING_ONLY));
		values.put(PotAction.VIEW_BALANCE, requirement(POT, BALANCE_VIEW, EXISTING_ONLY));
		return values;
	}

	private static PotActionRequirement requirement(
			AuthorizationTargetType targetType,
			com.kartaguez.pocoma.domain.authorization.Permission capability,
			AuthorizationTargetContract targetContract) {
		return new PotActionRequirement(targetType, capability, targetContract);
	}
}
