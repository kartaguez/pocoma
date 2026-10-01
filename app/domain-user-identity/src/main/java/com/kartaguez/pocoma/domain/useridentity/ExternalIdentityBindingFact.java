package com.kartaguez.pocoma.domain.useridentity;

import java.time.Instant;
import java.util.UUID;

/** Durable fact describing one successful mutation of the binding authority. */
public sealed interface ExternalIdentityBindingFact
		permits ExternalIdentityAttached, ExternalIdentityDetached {

	UUID eventId();

	ExternalIdentity externalIdentity();

	BindingId bindingId();

	BindingRevision bindingRevision();

	Instant recordedAt();
}
