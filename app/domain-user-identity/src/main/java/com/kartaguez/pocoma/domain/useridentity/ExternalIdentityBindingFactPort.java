package com.kartaguez.pocoma.domain.useridentity;

/** Append-only persistence boundary for binding lifecycle facts. */
public interface ExternalIdentityBindingFactPort {

	void append(ExternalIdentityBindingFact fact);

	java.util.Optional<ExternalIdentityBindingFact> findByEventId(java.util.UUID eventId);
}
