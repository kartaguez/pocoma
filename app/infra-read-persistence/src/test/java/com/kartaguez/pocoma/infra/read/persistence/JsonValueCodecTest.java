package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;

class JsonValueCodecTest {
	private final JsonValueCodec codec = new JsonValueCodec();

	@Test
	void roundTripsEveryJsonValueVariantAndNestedGraphs() {
		var value = new JsonObject(Map.of(
				"array", new JsonArray(List.of(
						new JsonString("text"),
						new JsonNumber(new BigDecimal("1234567890.123456789")),
						new JsonBoolean(true),
						JsonNull.INSTANCE)),
				"object", new JsonObject(Map.of("false", new JsonBoolean(false)))));

		assertEquals(value, codec.decode(codec.encode(value)));
	}
}
