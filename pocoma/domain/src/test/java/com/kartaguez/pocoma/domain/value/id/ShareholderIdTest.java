package com.kartaguez.pocoma.domain.value.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ShareholderIdTest {

	@Test
	void createsShareholderIdFromUuid() {
		UUID value = UUID.randomUUID();

		ShareholderId shareholderId = ShareholderId.of(value);

		assertEquals(value, shareholderId.value());
		assertEquals(new ShareholderId(value), shareholderId);
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> ShareholderId.of(null));
	}
}
