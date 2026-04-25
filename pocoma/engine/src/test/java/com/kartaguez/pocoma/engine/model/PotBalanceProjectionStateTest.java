package com.kartaguez.pocoma.engine.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;

class PotBalanceProjectionStateTest {

	@Test
	void createsPotBalanceProjectionState() {
		PotId potId = PotId.of(UUID.randomUUID());

		PotBalanceProjectionState state = new PotBalanceProjectionState(potId, 3);

		assertEquals(potId, state.potId());
		assertEquals(3, state.projectedVersion());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotBalanceProjectionState(null, 1));
	}

	@Test
	void rejectsVersionBelowOne() {
		assertThrows(
				IllegalArgumentException.class,
				() -> new PotBalanceProjectionState(PotId.of(UUID.randomUUID()), 0));
	}
}
