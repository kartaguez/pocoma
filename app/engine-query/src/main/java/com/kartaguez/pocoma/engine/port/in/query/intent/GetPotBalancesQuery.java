package com.kartaguez.pocoma.engine.port.in.query.intent;

import java.util.OptionalLong;
import java.util.UUID;

public record GetPotBalancesQuery(UUID potId, OptionalLong version) {

	public GetPotBalancesQuery {
		if (potId == null) {
			throw new NullPointerException("potId must not be null");
		}
		version = version == null ? OptionalLong.empty() : version;
		version.ifPresent(value -> {
			if (value < 1) {
				throw new IllegalArgumentException("version must be greater than or equal to 1");
			}
		});
	}

	public GetPotBalancesQuery(UUID potId) {
		this(potId, OptionalLong.empty());
	}
}
