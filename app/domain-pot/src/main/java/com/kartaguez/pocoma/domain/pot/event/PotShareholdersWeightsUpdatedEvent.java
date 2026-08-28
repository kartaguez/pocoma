package com.kartaguez.pocoma.domain.pot.event;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record PotShareholdersWeightsUpdatedEvent(PotId potId, Set<ShareholderId> shareholderIds, long version)
		implements BusinessEvent {

	public PotShareholdersWeightsUpdatedEvent {
		Objects.requireNonNull(potId, "potId must not be null");
		shareholderIds = Set.copyOf(Objects.requireNonNull(shareholderIds, "shareholderIds must not be null"));

		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
