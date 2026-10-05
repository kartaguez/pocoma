package com.kartaguez.pocoma.infra.persistence.projection.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	void roundTripsEveryJsonValueKind() {
		var value = new JsonObject(Map.of(
				"array", new JsonArray(List.of(new JsonString("text"), new JsonNumber(new BigDecimal("1.25")),
						new JsonBoolean(true), JsonNull.INSTANCE)),
				"object", new JsonObject(Map.of("false", new JsonBoolean(false)))));

		assertEquals(value, codec.decode(codec.encode(value)));
	}

	@Test
	void rejectsInvalidStoredJson() {
		assertThrows(IllegalStateException.class, () -> codec.decode("{"));
	}
}
