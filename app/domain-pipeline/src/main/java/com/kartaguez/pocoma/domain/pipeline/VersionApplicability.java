package com.kartaguez.pocoma.domain.pipeline;

import java.util.OptionalLong;

public record VersionApplicability(long applicableFromVersion, OptionalLong applicableThroughVersion) {

	public VersionApplicability {
		if (applicableFromVersion < 1) {
			throw new IllegalArgumentException("applicableFromVersion must be greater than or equal to 1");
		}
		if (applicableThroughVersion == null) {
			throw new NullPointerException("applicableThroughVersion must not be null");
		}
		if (applicableThroughVersion.isPresent()
				&& applicableThroughVersion.getAsLong() < applicableFromVersion) {
			throw new IllegalArgumentException("applicableThroughVersion must be greater than or equal to applicableFromVersion");
		}
	}

	public static VersionApplicability from(long applicableFromVersion) {
		return new VersionApplicability(applicableFromVersion, OptionalLong.empty());
	}

	public static VersionApplicability between(long applicableFromVersion, long applicableThroughVersion) {
		return new VersionApplicability(applicableFromVersion, OptionalLong.of(applicableThroughVersion));
	}

	public boolean appliesTo(long potVersion) {
		return potVersion >= applicableFromVersion
				&& (applicableThroughVersion.isEmpty() || potVersion <= applicableThroughVersion.getAsLong());
	}
}
