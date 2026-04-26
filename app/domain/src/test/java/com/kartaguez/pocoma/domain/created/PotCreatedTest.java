package com.kartaguez.pocoma.domain.created;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

class PotCreatedTest {

	@Test
	void createsPotCreated() {
		PotId id = PotId.of(UUID.randomUUID());
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		PotCreated potCreated = new PotCreated(id, label, creatorId);

		assertEquals(id, potCreated.id());
		assertEquals(label, potCreated.label());
		assertEquals(creatorId, potCreated.creatorId());
	}

	@Test
	void rejectsNullId() {
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new PotCreated(null, label, creatorId));
	}

	@Test
	void rejectsNullLabel() {
		PotId id = PotId.of(UUID.randomUUID());
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new PotCreated(id, null, creatorId));
	}

	@Test
	void rejectsNullCreatorId() {
		PotId id = PotId.of(UUID.randomUUID());
		Label label = Label.of("Trip");

		assertThrows(NullPointerException.class, () -> new PotCreated(id, label, null));
	}
}
