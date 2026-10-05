package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFact;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingFactRow;

public final class ExternalIdentityBindingFactRecordMapper {

	public ExternalIdentityBindingFactRow toRow(ExternalIdentityBindingFact fact) {
		requireNonNull(fact, "fact must not be null");
		UUID userId;
		String factType;
		if (fact instanceof ExternalIdentityAttached attached) {
			factType = "ATTACHED";
			userId = attached.userId().value();
		}
		else if (fact instanceof ExternalIdentityDetached detached) {
			factType = "DETACHED";
			userId = detached.userId().value();
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

	public ExternalIdentityBindingFact toFact(ExternalIdentityBindingFactRow row) {
		var identity = new ExternalIdentity(row.issuer(), row.subject());
		var bindingId = new BindingId(row.bindingId());
		var revision = new BindingRevision(row.bindingRevision());
		return switch (row.factType()) {
			case "ATTACHED" -> new ExternalIdentityAttached(row.eventId(), identity,
					new PocomaUserId(row.userId()), bindingId, revision, row.recordedAt());
			case "DETACHED" -> new ExternalIdentityDetached(row.eventId(), identity,
					new PocomaUserId(row.userId()), bindingId, revision, row.recordedAt());
			default -> throw new IllegalArgumentException("Unsupported binding fact type: " + row.factType());
		};
	}
}
