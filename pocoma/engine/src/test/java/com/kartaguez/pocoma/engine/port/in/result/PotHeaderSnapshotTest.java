package com.kartaguez.pocoma.engine.port.in.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

class PotHeaderSnapshotTest {

	@Test
	void createsPotHeaderSnapshot() {
		PotId id = PotId.of(UUID.randomUUID());
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		PotHeaderSnapshot snapshot = new PotHeaderSnapshot(id, label, creatorId, false, 1);

		assertEquals(id, snapshot.id());
		assertEquals(label, snapshot.label());
		assertEquals(creatorId, snapshot.creatorId());
		assertFalse(snapshot.deleted());
		assertEquals(1, snapshot.version());
	}

	@Test
	void rejectsNullId() {
		assertThrows(NullPointerException.class, () -> new PotHeaderSnapshot(
				null,
				Label.of("Trip"),
				UserId.of(UUID.randomUUID()),
				false,
				1));
	}

	@Test
	void rejectsNullLabel() {
		assertThrows(NullPointerException.class, () -> new PotHeaderSnapshot(
				PotId.of(UUID.randomUUID()),
				null,
				UserId.of(UUID.randomUUID()),
				false,
				1));
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> new PotHeaderSnapshot(
				PotId.of(UUID.randomUUID()),
				Label.of("Trip"),
				null,
				false,
				1));
	}

	@Test
	void rejectsVersionLowerThanOne() {
		assertThrows(IllegalArgumentException.class, () -> new PotHeaderSnapshot(
				PotId.of(UUID.randomUUID()),
				Label.of("Trip"),
				UserId.of(UUID.randomUUID()),
				false,
				0));
	}
}
