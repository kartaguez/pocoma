package com.kartaguez.pocoma.domain.pot.authorization;

import static java.util.Objects.requireNonNull;

/** Pure authorization outcome, deliberately independent from transport and pipeline states. */
public sealed interface AuthorizationDecision {

	static AuthorizationDecision allow() {
		return Allowed.INSTANCE;
	}

	static AuthorizationDecision deny(AuthorizationDenialReason reason) {
		return new Denied(reason);
	}

	default boolean isAllowed() {
		return this instanceof Allowed;
	}

	enum Allowed implements AuthorizationDecision {
		INSTANCE
	}

	record Denied(AuthorizationDenialReason reason) implements AuthorizationDecision {
		public Denied {
			requireNonNull(reason, "reason must not be null");
		}
	}
}
