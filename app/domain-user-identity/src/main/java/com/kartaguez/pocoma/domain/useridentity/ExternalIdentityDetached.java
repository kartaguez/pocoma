package com.kartaguez.pocoma.domain.useridentity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

/** Fact emitted for a successful detachment of one exact binding occurrence. */
public record ExternalIdentityDetached(
		UUID eventId,
		ExternalIdentity externalIdentity,
		BindingId bindingId,
		BindingRevision bindingRevision,
		Instant recordedAt) implements ExternalIdentityBindingFact {

	public ExternalIdentityDetached {
		requireNonNull(eventId, "eventId must not be null");
		requireNonNull(externalIdentity, "externalIdentity must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requirePositive(bindingRevision);
		requireNonNull(recordedAt, "recordedAt must not be null");
	}

	private static void requirePositive(BindingRevision revision) {
		requireNonNull(revision, "bindingRevision must not be null");
		if (revision.value() == 0) throw new IllegalArgumentException("bindingRevision must be positive for a fact");
	}
}
