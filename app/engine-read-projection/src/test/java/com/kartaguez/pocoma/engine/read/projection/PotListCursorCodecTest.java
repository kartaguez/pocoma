package com.kartaguez.pocoma.engine.read.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class PotListCursorCodecTest {

	private final PotListCursorCodec codec = new PotListCursorCodec();

	@Test
	void roundTripsAnOpaqueVersionedCursor() {
		var cursor = new PotListCursor(
				Instant.parse("2026-09-10T05:30:15.123456789Z"),
				PotId.of(UUID.fromString("00000000-0000-0000-0000-000000000123")));

		assertEquals(cursor, codec.decode(codec.encode(cursor)));
	}

	@Test
	void rejectsMalformedAndUnknownVersionCursors() {
		assertThrows(IllegalArgumentException.class, () -> codec.decode("not-base64!"));
		assertThrows(IllegalArgumentException.class, () -> codec.decode(" "));

		var payload = ByteBuffer.allocate(Integer.BYTES).putInt(2).array();
		var unknownVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
		assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownVersion));
	}
}
