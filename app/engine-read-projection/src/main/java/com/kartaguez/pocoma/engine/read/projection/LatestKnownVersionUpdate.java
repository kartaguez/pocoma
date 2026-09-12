package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.LatestKnownVersion;

public sealed interface LatestKnownVersionUpdate {

	LatestKnownVersion latestKnownVersion();

	record Advanced(LatestKnownVersion latestKnownVersion) implements LatestKnownVersionUpdate {
		public Advanced { requireNonNull(latestKnownVersion, "latestKnownVersion must not be null"); }
	}

	record Unchanged(LatestKnownVersion latestKnownVersion) implements LatestKnownVersionUpdate {
		public Unchanged { requireNonNull(latestKnownVersion, "latestKnownVersion must not be null"); }
	}
}
