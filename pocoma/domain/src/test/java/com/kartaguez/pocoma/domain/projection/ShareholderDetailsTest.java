package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ShareholderDetailsTest {

	@Test
	void createsShareholderDetails() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Name name = Name.of("Alice");
		UserId userId = UserId.of(UUID.randomUUID());

		ShareholderDetails details = new ShareholderDetails(shareholderId, name, userId);

		assertEquals(shareholderId, details.shareholderId());
		assertEquals(name, details.name());
		assertEquals(userId, details.userId());
	}

	@Test
	void rejectsNullShareholderId() {
		Name name = Name.of("Alice");

		assertThrows(NullPointerException.class, () -> new ShareholderDetails(null, name, null));
	}

	@Test
	void rejectsNullName() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new ShareholderDetails(shareholderId, null, null));
	}

	@Test
	void acceptsNullUserId() {
		ShareholderDetails details = new ShareholderDetails(ShareholderId.of(UUID.randomUUID()), Name.of("Alice"), null);

		assertNull(details.userId());
	}
}
