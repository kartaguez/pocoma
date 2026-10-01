package com.kartaguez.pocoma.engine.read.binding;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

public record CurrentBinding(ExternalIdentity externalIdentity, BindingRevision bindingRevision,
		CurrentBindingStatus status, PocomaUserId userId, BindingId bindingId,
		UUID sourceEventId, Instant projectedAt) {
	public CurrentBinding {
		requireNonNull(externalIdentity, "externalIdentity must not be null");
		requireNonNull(bindingRevision, "bindingRevision must not be null");
		requireNonNull(status, "status must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requireNonNull(projectedAt, "projectedAt must not be null");
		if ((status == CurrentBindingStatus.ATTACHED) != (userId != null)) {
			throw new IllegalArgumentException("userId is required exactly for ATTACHED");
		}
		if ((bindingRevision.value() == 0) != (sourceEventId == null)) {
			throw new IllegalArgumentException("sourceEventId is null exactly for revision zero");
		}
	}

	public boolean samePayload(CurrentBinding other) {
		return externalIdentity.equals(other.externalIdentity)
				&& bindingRevision.equals(other.bindingRevision) && status == other.status
				&& java.util.Objects.equals(userId, other.userId) && bindingId.equals(other.bindingId)
				&& java.util.Objects.equals(sourceEventId, other.sourceEventId);
	}
}
