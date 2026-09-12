package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Highest Business Event version materialized by the read side for one Pot. */
public record LatestKnownVersion(PotId potId, long latestKnownVersion) {

	public LatestKnownVersion {
		requireNonNull(potId, "potId must not be null");
		if (latestKnownVersion < 1) {
			throw new IllegalArgumentException("latestKnownVersion must be greater than or equal to 1");
		}
	}
}
