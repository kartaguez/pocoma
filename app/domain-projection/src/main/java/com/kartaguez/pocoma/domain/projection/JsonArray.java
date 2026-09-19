package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.List;

public record JsonArray(List<JsonValue> values) implements JsonValue {
	public JsonArray {
		requireNonNull(values, "values must not be null");
		values = List.copyOf(values);
	}
}
