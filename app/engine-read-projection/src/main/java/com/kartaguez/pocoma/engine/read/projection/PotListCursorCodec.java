package com.kartaguez.pocoma.engine.read.projection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

public final class PotListCursorCodec {
	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_ENCODED_LENGTH = 128;

	public String encode(PotListCursor cursor) {
		requireNonNull(cursor, "cursor must not be null");
		try {
			var bytes = new ByteArrayOutputStream();
			try (var output = new DataOutputStream(bytes)) {
				output.writeInt(SCHEMA_VERSION);
				output.writeLong(cursor.updatedAt().getEpochSecond());
				output.writeInt(cursor.updatedAt().getNano());
				output.writeLong(cursor.potId().value().getMostSignificantBits());
				output.writeLong(cursor.potId().value().getLeastSignificantBits());
			}
			return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot encode Pot list cursor", exception);
		}
	}

	public PotListCursor decode(String encoded) {
		if (encoded == null || encoded.isBlank() || encoded.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("invalid Pot list cursor");
		}
		try {
			byte[] bytes = Base64.getUrlDecoder().decode(encoded);
			try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
				int version = input.readInt();
				if (version != SCHEMA_VERSION) {
					throw new IllegalArgumentException("unsupported Pot list cursor version");
				}
				var instant = Instant.ofEpochSecond(input.readLong(), input.readInt());
				var potId = PotId.of(new UUID(input.readLong(), input.readLong()));
				if (input.available() != 0) {
					throw new IllegalArgumentException("invalid Pot list cursor payload");
				}
				return new PotListCursor(instant, potId);
			}
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof IllegalArgumentException illegalArgumentException) {
				throw illegalArgumentException;
			}
			throw new IllegalArgumentException("invalid Pot list cursor", exception);
		}
	}
}
