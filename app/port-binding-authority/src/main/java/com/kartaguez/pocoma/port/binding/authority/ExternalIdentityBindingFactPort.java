package com.kartaguez.pocoma.port.binding.authority;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;

/** Append-only persistence boundary for binding lifecycle facts. */
public interface ExternalIdentityBindingFactPort {

	com.kartaguez.pocoma.port.transaction.EventRef append(ExternalIdentityBindingFact fact);

	java.util.Optional<ExternalIdentityBindingFact> findByEventId(java.util.UUID eventId);
}
