package com.kartaguez.pocoma.domain.pot.projection.definition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;

final class PotProjectionSchemas {
	private static final JsonValue UUID = object(Map.of("type", string("string"), "format", string("uuid")));
	private static final JsonValue DATE = object(Map.of("type", string("string"), "format", string("date")));
	private static final JsonValue TEXT = object(Map.of("type", string("string")));
	private static final JsonValue NON_NEGATIVE_FRACTION = fraction(0);
	private static final JsonValue SIGNED_FRACTION = fraction(Long.MIN_VALUE);

	private PotProjectionSchemas() {}

	static JsonValue creator() { return strict(Map.of("userId", UUID), List.of("userId")); }
	static JsonValue shareholderUser() { return strict(Map.of("userId", UUID, "shareholderId", UUID), List.of("userId", "shareholderId")); }
	static JsonValue pot() { return strict(Map.of("potId", UUID, "name", TEXT), List.of("potId", "name")); }
	static JsonValue shareholder() {
		JsonValue nullableUuid = object(Map.of("anyOf", array(UUID, object(Map.of("type", string("null"))))));
		return strict(Map.of("shareholderId", UUID, "name", TEXT, "userId", nullableUuid,
				"part", NON_NEGATIVE_FRACTION), List.of("shareholderId", "name", "userId", "part"));
	}
	static JsonValue expense() {
		JsonValue share = strict(Map.of("shareholderId", UUID, "part", NON_NEGATIVE_FRACTION),
				List.of("shareholderId", "part"));
		return strict(Map.of("expenseId", UUID, "name", TEXT, "amount", NON_NEGATIVE_FRACTION,
				"date", DATE, "payerShareholderId", UUID,
				"shares", object(Map.of("type", string("array"), "items", share))),
				List.of("expenseId", "name", "amount", "date", "payerShareholderId", "shares"));
	}
	static JsonValue balance() {
		return strict(Map.of("shareholderId", UUID, "balance", SIGNED_FRACTION),
				List.of("shareholderId", "balance"));
	}

	private static JsonValue fraction(long minimum) {
		return strict(Map.of("numerator", integer(minimum), "denominator", integer(1)),
				List.of("numerator", "denominator"));
	}
	private static JsonValue strict(Map<String, JsonValue> properties, List<String> required) {
		return object(Map.of("type", string("object"), "properties", object(properties),
				"required", new JsonArray(required.stream().map(JsonString::new).map(JsonValue.class::cast).toList()),
				"additionalProperties", new JsonBoolean(false)));
	}
	private static JsonValue integer(long minimum) {
		return object(Map.of("type", string("integer"), "minimum", number(minimum),
				"maximum", number(Long.MAX_VALUE)));
	}
	private static JsonObject object(Map<String, JsonValue> values) { return new JsonObject(values); }
	private static JsonArray array(JsonValue... values) { return new JsonArray(List.of(values)); }
	private static JsonString string(String value) { return new JsonString(value); }
	private static JsonNumber number(long value) { return new JsonNumber(BigDecimal.valueOf(value)); }
}
