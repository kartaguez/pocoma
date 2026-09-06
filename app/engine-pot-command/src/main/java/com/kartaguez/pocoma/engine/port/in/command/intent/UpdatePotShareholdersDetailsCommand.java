package com.kartaguez.pocoma.engine.port.in.command.intent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.kartaguez.pocoma.engine.command.model.Command;

public record UpdatePotShareholdersDetailsCommand(UUID potId, Set<ShareholderDetailsInput> shareholders, long expectedVersion)
		implements Command {

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
