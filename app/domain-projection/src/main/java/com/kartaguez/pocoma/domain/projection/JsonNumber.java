package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;

public record JsonNumber(BigDecimal value) implements JsonValue {
	public JsonNumber {
		requireNonNull(value, "value must not be null");
	}
}
