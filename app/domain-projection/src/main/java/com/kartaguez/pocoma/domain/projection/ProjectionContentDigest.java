package com.kartaguez.pocoma.domain.projection;

import java.util.Objects;
import java.util.regex.Pattern;

public record ProjectionContentDigest(String value) {
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
	public ProjectionContentDigest {
		Objects.requireNonNull(value, "value must not be null");
		if (!SHA_256.matcher(value).matches()) throw new IllegalArgumentException("value must be a lowercase SHA-256 digest");
	}
}
