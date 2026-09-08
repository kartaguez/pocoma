package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class SourceVersionWatermarkTest {

	@Test
	void retainsOnlyFunctionalSourceVersionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		var watermark = new SourceVersionWatermark(potId, 42);

		assertEquals(potId, watermark.potId());
		assertEquals(42, watermark.latestVersionSeen());
		assertEquals(2, SourceVersionWatermark.class.getRecordComponents().length);
	}

	@Test
	void rejectsNonPositiveVersion() {
		assertThrows(IllegalArgumentException.class,
				() -> new SourceVersionWatermark(PotId.of(UUID.randomUUID()), 0));
	}
}
