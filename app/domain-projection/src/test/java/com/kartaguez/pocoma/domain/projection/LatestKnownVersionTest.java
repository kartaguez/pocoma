package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

class LatestKnownVersionTest {

	@Test
	void retainsOnlyFunctionalSourceVersionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		var latestKnownVersion = new LatestKnownVersion(potId, 42);

		assertEquals(potId, latestKnownVersion.potId());
		assertEquals(42, latestKnownVersion.latestKnownVersion());
		assertEquals(2, LatestKnownVersion.class.getRecordComponents().length);
	}

	@Test
	void rejectsNonPositiveVersion() {
		assertThrows(IllegalArgumentException.class,
				() -> new LatestKnownVersion(PotId.of(UUID.randomUUID()), 0));
	}
}
