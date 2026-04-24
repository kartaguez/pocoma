package com.kartaguez.pocoma.engine.model;

import java.util.Objects;

public record Versioned<T>(T value, long startedAtVersion, Long endedAtVersion) {

	public Versioned {
		Objects.requireNonNull(value, "value must not be null");
		if (startedAtVersion < 1) {
			throw new IllegalArgumentException("startedAtVersion must be greater than or equal to 1");
		}
		if (endedAtVersion != null && endedAtVersion <= startedAtVersion) {
			throw new IllegalArgumentException("endedAtVersion must be greater than startedAtVersion");
		}
	}

	public boolean isActiveAt(long version) {
		if (version < 1) {
			throw new IllegalArgumentException("version must be greater than or equal to 1");
		}

		return startedAtVersion <= version && (endedAtVersion == null || version < endedAtVersion);
	}
}
