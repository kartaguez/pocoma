package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason.CONFIGURATION_ERROR;
import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;

/** I/O-free orchestration of current capabilities and Pot business facts. */
public final class AuthorizationKernel {

	private final PotActionRequirements requirements;
	private final TokenCapabilityPolicy tokenPolicy;
	private final PotBusinessAuthorizationPolicy businessPolicy;

	public AuthorizationKernel() {
		this(new PotActionRequirements(), new TokenCapabilityPolicy(), new PotBusinessAuthorizationPolicy());
	}

	public AuthorizationKernel(
			PotActionRequirements requirements,
			TokenCapabilityPolicy tokenPolicy,
			PotBusinessAuthorizationPolicy businessPolicy) {
		this.requirements = requireNonNull(requirements, "requirements must not be null");
		this.tokenPolicy = requireNonNull(tokenPolicy, "tokenPolicy must not be null");
		this.businessPolicy = requireNonNull(businessPolicy, "businessPolicy must not be null");
	}

	public AuthorizationDecision decide(
			TokenCapabilities tokenCapabilities,
			RequiredCurrentCapabilities requiredCapabilities,
			AuthorizationTarget target,
			AuthorizationFacts facts,
			PotAction action) {
		requireNonNull(tokenCapabilities, "tokenCapabilities must not be null");
		requireNonNull(requiredCapabilities, "requiredCapabilities must not be null");
		requireNonNull(target, "target must not be null");
		requireNonNull(facts, "facts must not be null");
		requireNonNull(action, "action must not be null");

		PotActionRequirement requirement = requirements.find(action).orElse(null);
		if (requirement == null || !requirement.accepts(target)
				|| !requiredCapabilities.contains(requirement.baseCapability())) {
			return AuthorizationDecision.deny(CONFIGURATION_ERROR);
		}

		AuthorizationDecision capabilityDecision = tokenPolicy.decide(tokenCapabilities, requiredCapabilities);
		if (!capabilityDecision.isAllowed()) return capabilityDecision;
		return businessPolicy.decide(target.targetType(), facts, action);
	}
}
