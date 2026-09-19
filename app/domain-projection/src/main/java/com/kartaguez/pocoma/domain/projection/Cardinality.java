package com.kartaguez.pocoma.domain.projection;

public record Cardinality(int min, Integer max) {
	public Cardinality {
		if (min < 0) {
			throw new IllegalArgumentException("min must be greater than or equal to 0");
		}
		if (max != null && max < min) {
			throw new IllegalArgumentException("max must be greater than or equal to min");
		}
	}

	public boolean accepts(int count) {
		return count >= min && (max == null || count <= max);
	}
}
