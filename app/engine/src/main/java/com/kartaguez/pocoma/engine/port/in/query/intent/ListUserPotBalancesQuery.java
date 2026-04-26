package com.kartaguez.pocoma.engine.port.in.query.intent;

import java.util.OptionalLong;

public record ListUserPotBalancesQuery(OptionalLong version) {

	public ListUserPotBalancesQuery {
		version = version == null ? OptionalLong.empty() : version;
		version.ifPresent(value -> {
			if (value < 1) {
				throw new IllegalArgumentException("version must be greater than or equal to 1");
			}
		});
	}

	public ListUserPotBalancesQuery() {
		this(OptionalLong.empty());
	}
}
