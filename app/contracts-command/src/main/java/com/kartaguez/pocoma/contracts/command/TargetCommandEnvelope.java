package com.kartaguez.pocoma.contracts.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.identity.BindingId;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;

/** Target V2 envelope captured without resolving a Pocoma User at admission. */
public record TargetCommandEnvelope(
		ExternalIdentity externalIdentity,
		BindingId bindingId,
		CommandAuthenticationEvidence authenticationEvidence) {

	public TargetCommandEnvelope {
		requireNonNull(externalIdentity, "externalIdentity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requireNonNull(authenticationEvidence, "authenticationEvidence must not be null");
	}

}
