package com.kartaguez.pocoma.engine.port.in.query.intent;

import java.util.OptionalLong;
import java.util.UUID;

public record GetPotQuery(UUID potId, OptionalLong version) {

	public GetPotQuery {
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

	public GetPotQuery(UUID potId) {
		this(potId, OptionalLong.empty());
	}
}
