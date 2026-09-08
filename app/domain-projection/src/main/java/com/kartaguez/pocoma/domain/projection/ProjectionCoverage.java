package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record ProjectionCoverage(ProjectionGenerationIdentity generation, long fromVersion, long throughVersion) {
	public ProjectionCoverage {
		requireNonNull(generation, "generation must not be null");
		if (fromVersion < 1) throw new IllegalArgumentException("fromVersion must be greater than or equal to 1");
		if (throughVersion < fromVersion) throw new IllegalArgumentException("throughVersion must be greater than or equal to fromVersion");
	}

	public boolean contains(long version) { return version >= fromVersion && version <= throughVersion; }
	public ProjectionCoverage extendThrough(long version) {
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		return new ProjectionCoverage(generation, fromVersion, Math.max(throughVersion, version));
	}
	public ProjectionCoverage extendFrom(long version) {
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		return new ProjectionCoverage(generation, Math.min(fromVersion, version), throughVersion);
	}
}
