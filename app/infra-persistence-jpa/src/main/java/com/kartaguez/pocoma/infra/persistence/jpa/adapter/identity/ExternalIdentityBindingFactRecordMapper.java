package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityBindingFactRow;

public final class ExternalIdentityBindingFactRecordMapper {

	public ExternalIdentityBindingFactRow toRow(ExternalIdentityBindingFact fact) {
		requireNonNull(fact, "fact must not be null");
		UUID userId;
		String factType;
		if (fact instanceof ExternalIdentityAttached attached) {
			factType = "ATTACHED";
			userId = attached.userId().value();
		}
		else if (fact instanceof ExternalIdentityDetached) {
			factType = "DETACHED";
			userId = null;
		}
		else {
			throw new IllegalArgumentException("Unsupported binding fact: " + fact.getClass().getName());
		}
		return new ExternalIdentityBindingFactRow(
				fact.eventId(),
				fact.externalIdentity().issuer(),
				fact.externalIdentity().subject(),
				fact.bindingRevision().value(),
				factType,
				userId,
				fact.bindingId().value(),
				fact.recordedAt());
	}
}
