package com.kartaguez.pocoma.domain.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.created.PotCreated;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;

class PotFactoryTest {

	@Test
	void createsPot() {
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		PotCreated potCreated = PotFactory.createPot(label, creatorId);

		assertNotNull(potCreated.id());
		assertEquals(label, potCreated.label());
		assertEquals(creatorId, potCreated.creatorId());
	}

	@Test
	void rejectsNullLabel() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> PotFactory.createPot(null, creatorId));
	}

	@Test
	void rejectsNullCreatorId() {
		Label label = Label.of("Trip");

		assertThrows(NullPointerException.class, () -> PotFactory.createPot(label, null));
	}
}
