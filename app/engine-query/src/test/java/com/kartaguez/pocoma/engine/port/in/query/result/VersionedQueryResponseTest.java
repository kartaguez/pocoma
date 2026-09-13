package com.kartaguez.pocoma.engine.port.in.query.result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.port.in.query.version.QueryVersionIntent;

class VersionedQueryResponseTest {

	private static final Instant GENERATED_AT = Instant.parse("2026-09-13T10:15:30Z");

	@Test
	void preservesCurrentIntentVersionsTimestampAndGenericData() {
		QueryVersionIntent intent = QueryVersionIntent.current();
		Object data = new Object();

		VersionedQueryResponse<Object> response = new VersionedQueryResponse<>(
				intent, 13, 15, GENERATED_AT, data);

		assertEquals(intent, response.requestedVersion());
		assertEquals(13, response.servedVersion());
		assertEquals(15, response.latestKnownVersion());
		assertEquals(GENERATED_AT, response.generatedAt());
		assertSame(data, response.data());
	}

	@Test
	void exactIntentRequiresItsRequestedBusinessVersion() {
		VersionedQueryResponse<String> response = new VersionedQueryResponse<>(
				QueryVersionIntent.exact(14), 14, 15, GENERATED_AT, "pot");

		assertEquals(14, response.servedVersion());
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(
						QueryVersionIntent.exact(14), 13, 15, GENERATED_AT, "pot"));
	}

	@Test
	void rejectsMissingRequiredFields() {
		assertThrows(NullPointerException.class,
				() -> new VersionedQueryResponse<>(null, 1, 1, GENERATED_AT, "data"));
		assertThrows(NullPointerException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 1, 1, null, "data"));
		assertThrows(NullPointerException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 1, 1, GENERATED_AT, null));
	}

	@Test
	void rejectsInvalidExposureVersions() {
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 0, 1, GENERATED_AT, "data"));
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), -1, 1, GENERATED_AT, "data"));
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 1, 0, GENERATED_AT, "data"));
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 1, -1, GENERATED_AT, "data"));
		assertThrows(IllegalArgumentException.class,
				() -> new VersionedQueryResponse<>(QueryVersionIntent.current(), 16, 15, GENERATED_AT, "data"));
	}

	@Test
	void exposesNoStaleField() {
		assertArrayEquals(
				new String[] {"requestedVersion", "servedVersion", "latestKnownVersion", "generatedAt", "data"},
				Arrays.stream(VersionedQueryResponse.class.getRecordComponents())
						.map(component -> component.getName())
						.toArray(String[]::new));
	}
}
