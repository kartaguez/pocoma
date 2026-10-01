package com.kartaguez.pocoma.engine.command.result;

import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

/** READ-only compatibility port for authorizing historical V1 results by current user binding. */
@FunctionalInterface
public interface LegacyCurrentBindingUserQuery {
	Optional<UUID> findAttachedUser(ExternalIdentity identity);
}
