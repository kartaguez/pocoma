package com.kartaguez.pocoma.engine.port.out.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.legacy.LatestKnownVersion;

class LatestKnownVersionQueryPortContractTest {

	@Test
	void distinguishesKnownVersionFromAbsence() {
		PotId knownPot = PotId.of(UUID.randomUUID());
		PotId unknownPot = PotId.of(UUID.randomUUID());
		LatestKnownVersion expected = new LatestKnownVersion(knownPot, 15);
		LatestKnownVersionQueryPort port = potId -> Optional.ofNullable(Map.of(knownPot, expected).get(potId));

		assertEquals(Optional.of(expected), port.findByPotId(knownPot));
		assertEquals(Optional.empty(), port.findByPotId(unknownPot));
	}
}
