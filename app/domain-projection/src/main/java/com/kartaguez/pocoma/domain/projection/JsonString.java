package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

public record JsonString(String value) implements JsonValue {
	public JsonString {
		requireNonNull(value, "value must not be null");
	}
}
