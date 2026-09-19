package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.Map;

public record JsonObject(Map<String, JsonValue> values) implements JsonValue {
	public JsonObject {
		requireNonNull(values, "values must not be null");
		values = Map.copyOf(values);
	}
}
