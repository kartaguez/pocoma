package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/** Fact emitted for a successful attachment of one exact binding occurrence. */
public record ExternalIdentityAttached(
		UUID eventId,
		ExternalIdentity externalIdentity,
		PocomaUserId userId,
		BindingId bindingId,
		BindingRevision bindingRevision,
		Instant recordedAt) implements ExternalIdentityBindingFact {

	public ExternalIdentityAttached {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(externalIdentity, "externalIdentity must not be null");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requireNonNull(bindingRevision, "bindingRevision must not be null");
		requireNonNull(recordedAt, "recordedAt must not be null");
	}
}
