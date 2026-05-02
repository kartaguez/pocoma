package com.kartaguez.pocoma.engine.port.in.command.result;

import java.util.Objects;
import java.util.Set;

import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.id.PotId;

public record PotShareholdersSnapshot(PotId potId, Set<Shareholder> shareholders, long version) {

	public PotShareholdersSnapshot {
		Objects.requireNonNull(potId, "potId must not be null");
		shareholders = Set.copyOf(Objects.requireNonNull(shareholders, "shareholders must not be null"));
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}
	}
}
