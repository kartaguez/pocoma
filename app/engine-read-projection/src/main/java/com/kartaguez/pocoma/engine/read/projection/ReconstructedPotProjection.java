package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.PotVersionMetadata;

public record ReconstructedPotProjection(
		PotProjection projection,
		PotVersionMetadata versionMetadata) {

	public ReconstructedPotProjection {
		requireNonNull(projection, "projection must not be null");
		requireNonNull(versionMetadata, "versionMetadata must not be null");
		if (!projection.identity().generation().potId().equals(versionMetadata.potId())
				|| projection.identity().potVersion() != versionMetadata.version()) {
			throw new IllegalArgumentException("projection and version metadata identities must match");
		}
	}
}
