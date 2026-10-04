package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.authorization.Permission;

public record PotActionRequirement(
		AuthorizationTargetType targetType,
		Permission baseCapability,
		AuthorizationTargetContract targetContract) {

	public PotActionRequirement {
		requireNonNull(targetType, "targetType must not be null");
		requireNonNull(baseCapability, "baseCapability must not be null");
		requireNonNull(targetContract, "targetContract must not be null");
	}

	public boolean accepts(AuthorizationTarget target) {
		requireNonNull(target, "target must not be null");
		return target.targetType() == targetType
				&& (targetContract == AuthorizationTargetContract.PROSPECTIVE_ALLOWED || target.existing());
	}
}
