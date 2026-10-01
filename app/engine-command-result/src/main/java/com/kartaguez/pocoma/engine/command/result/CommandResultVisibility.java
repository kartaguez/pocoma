package com.kartaguez.pocoma.engine.command.result;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

public sealed interface CommandResultVisibility
		permits CommandResultVisibility.LegacyUser, CommandResultVisibility.ExactExternalIdentity {

	record LegacyUser(UUID userId) implements CommandResultVisibility {
		public LegacyUser { requireNonNull(userId, "userId must not be null"); }
	}

	record ExactExternalIdentity(ExternalIdentity identity) implements CommandResultVisibility {
		public ExactExternalIdentity { requireNonNull(identity, "identity must not be null"); }
	}
}
