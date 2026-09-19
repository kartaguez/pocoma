package com.kartaguez.pocoma.engine.pot.read;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;

final class JsonValueReader {
	private JsonValueReader() {
	}

	static Map<String, JsonValue> object(JsonValue value) {
		if (value instanceof JsonObject object) {
			return object.values();
		}
		throw new IllegalArgumentException("expected JSON object");
	}

	static List<JsonValue> array(JsonValue value) {
		if (value instanceof JsonArray array) {
			return array.values();
		}
		throw new IllegalArgumentException("expected JSON array");
	}

	static JsonValue required(Map<String, JsonValue> object, String field) {
		JsonValue value = object.get(field);
		if (value == null) {
			throw new IllegalArgumentException("missing JSON field " + field);
		}
		return value;
	}

	static String string(Map<String, JsonValue> object, String field) {
		JsonValue value = required(object, field);
		if (value instanceof JsonString string) {
			return string.value();
		}
		throw new IllegalArgumentException("expected JSON string for " + field);
	}

	static UUID uuid(Map<String, JsonValue> object, String field) {
		return UUID.fromString(string(object, field));
	}

	static long integer(Map<String, JsonValue> object, String field) {
		JsonValue value = required(object, field);
		if (value instanceof JsonNumber number) {
			return number.value().longValueExact();
		}
		throw new IllegalArgumentException("expected JSON number for " + field);
	}
}
