package com.kartaguez.pocoma.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotGlobalVersionTest {

	@Test
	void createsPotGlobalVersion() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotGlobalVersion potGlobalVersion = new PotGlobalVersion(potId, 1);

		assertEquals(potId, potGlobalVersion.potId());
		assertEquals(1, potGlobalVersion.version());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotGlobalVersion(null, 1));
	}

	@Test
	void rejectsVersionLowerThanOne() {
		PotId potId = PotId.of(UUID.randomUUID());

		assertThrows(IllegalArgumentException.class, () -> new PotGlobalVersion(potId, 0));
	}
}
