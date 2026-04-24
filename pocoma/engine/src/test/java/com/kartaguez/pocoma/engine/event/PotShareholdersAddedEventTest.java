package com.kartaguez.pocoma.engine.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class PotShareholdersAddedEventTest {

	@Test
	void createsPotShareholdersAddedEvent() {
		PotId potId = PotId.of(UUID.randomUUID());
		Set<ShareholderId> shareholderIds = Set.of(ShareholderId.of(UUID.randomUUID()));

		PotShareholdersAddedEvent event = new PotShareholdersAddedEvent(potId, shareholderIds, 2);

		assertEquals(potId, event.potId());
		assertEquals(shareholderIds, event.shareholderIds());
		assertEquals(2, event.version());
	}

	@Test
	void rejectsNullPotId() {
		assertThrows(NullPointerException.class, () -> new PotShareholdersAddedEvent(
				null,
				Set.of(ShareholderId.of(UUID.randomUUID())),
				2));
	}

	@Test
	void rejectsNullShareholderIds() {
		assertThrows(NullPointerException.class, () -> new PotShareholdersAddedEvent(
				PotId.of(UUID.randomUUID()),
				null,
				2));
	}

	@Test
	void rejectsInvalidVersion() {
		assertThrows(IllegalArgumentException.class, () -> new PotShareholdersAddedEvent(
				PotId.of(UUID.randomUUID()),
				Set.of(ShareholderId.of(UUID.randomUUID())),
				0));
	}
}
