package com.kartaguez.pocoma.engine.port.in.intent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UpdatePotShareholdersDetailsCommand(UUID potId, Set<ShareholderDetailsInput> shareholders, long expectedVersion) {

	public UpdatePotShareholdersDetailsCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		shareholders = Set.copyOf(Objects.requireNonNull(shareholders, "shareholders must not be null"));

		if (shareholders.isEmpty()) {
			throw new IllegalArgumentException("shareholders must not be empty");
		}
		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}

	public record ShareholderDetailsInput(UUID shareholderId, String name, UUID userId) {

		public ShareholderDetailsInput {
			Objects.requireNonNull(shareholderId, "shareholderId must not be null");
			Objects.requireNonNull(name, "name must not be null");
		}
	}
}
