package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Highest source version observed by the read side for one Pot. */
public record SourceVersionWatermark(PotId potId, long latestVersionSeen) {

	public SourceVersionWatermark {
		requireNonNull(potId, "potId must not be null");
		if (latestVersionSeen < 1) {
			throw new IllegalArgumentException("latestVersionSeen must be greater than or equal to 1");
		}
	}
}
