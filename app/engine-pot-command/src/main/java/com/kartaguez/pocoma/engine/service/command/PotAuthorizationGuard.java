package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.authorization.RequiredCurrentCapabilities;
import com.kartaguez.pocoma.domain.authorization.TokenCapabilities;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDecision;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationDenialReason;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationKernel;
import com.kartaguez.pocoma.domain.pot.authorization.AuthorizationTarget;
import com.kartaguez.pocoma.domain.pot.authorization.PotAction;
import com.kartaguez.pocoma.domain.pot.authorization.PotActionRequirement;
import com.kartaguez.pocoma.domain.pot.authorization.PotActionRequirements;
import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationFactResolver;
import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationRelations;
import com.kartaguez.pocoma.domain.pot.authorization.PotBusinessAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.authorization.TokenCapabilityPolicy;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;

/** Adapts the pure authorization kernel to the write-side exception contract. */
public final class PotAuthorizationGuard {

	private final PotActionRequirements requirements;
	private final PotAuthorizationFactResolver factResolver;
	private final AuthorizationKernel kernel;

	public PotAuthorizationGuard() {
		this(new PotActionRequirements(), new PotAuthorizationFactResolver());
	}

	PotAuthorizationGuard(
			PotActionRequirements requirements,
			PotAuthorizationFactResolver factResolver) {
		this.requirements = requireNonNull(requirements, "requirements must not be null");
		this.factResolver = requireNonNull(factResolver, "factResolver must not be null");
		this.kernel = new AuthorizationKernel(
				this.requirements,
				new TokenCapabilityPolicy(),
				new PotBusinessAuthorizationPolicy());
	}

	public void assertAuthorized(
			UserId userId,
			Set<Permission> permissions,
			PotAuthorizationRelations relations,
			AuthorizationTarget target,
			PotAction action,
			String businessDenialCode,
			String businessDenialMessage) {
		requireNonNull(permissions, "permissions must not be null");
		requireNonNull(relations, "relations must not be null");
		requireNonNull(target, "target must not be null");
		requireNonNull(action, "action must not be null");
		requireNonNull(businessDenialCode, "businessDenialCode must not be null");
		requireNonNull(businessDenialMessage, "businessDenialMessage must not be null");
		if (userId == null) {
			throw new BusinessRuleViolationException("ANONYMOUS_USER", "Anonymous users are not authorized");
		}

		PotActionRequirement requirement = requirements.find(action).orElse(null);
		if (requirement == null) {
			throw configurationError(action);
		}
		AuthorizationDecision decision = kernel.decide(
				new TokenCapabilities(permissions),
				RequiredCurrentCapabilities.of(requirement.baseCapability()),
				target,
				factResolver.resolve(relations, userId, target),
				action);
		if (decision.isAllowed()) return;

		AuthorizationDenialReason reason = ((AuthorizationDecision.Denied) decision).reason();
		throw switch (reason) {
			case MISSING_CAPABILITY -> new BusinessRuleViolationException(
					"MISSING_PERMISSION", "User is missing a required permission");
			case BUSINESS_POLICY_DENIED -> new BusinessRuleViolationException(
					businessDenialCode, businessDenialMessage);
			case CONFIGURATION_ERROR -> configurationError(action);
		};
	}

	private static BusinessRuleViolationException configurationError(PotAction action) {
		return new BusinessRuleViolationException(
				"AUTHORIZATION_CONFIGURATION_ERROR",
				"Authorization contract is not configured consistently for action " + action);
	}
}
