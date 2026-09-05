package com.kartaguez.pocoma.orchestrator.command.admission;

import com.kartaguez.pocoma.orchestrator.command.admission.model.ExternalIdentity;

public final class UserNotProvisionedException extends RuntimeException {
	private final ExternalIdentity identity;

	public UserNotProvisionedException(ExternalIdentity identity) {
		super("No Pocoma user is provisioned for external identity");
		this.identity = identity;
	}

	public ExternalIdentity identity() {
		return identity;
	}
}
