package com.kartaguez.pocoma.domain.draft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;

class ShareholderDraftTest {

	@Test
	void createsShareholderDraft() {
		Name name = Name.of("Alice");
		Weight weight = Weight.of(new Fraction(1, 2));
		UserId userId = UserId.of(UUID.randomUUID());

		ShareholderDraft draft = new ShareholderDraft(name, weight, userId);

		assertEquals(name, draft.name());
		assertEquals(weight, draft.weight());
		assertEquals(userId, draft.userId());
	}

	@Test
	void rejectsNullName() {
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> new ShareholderDraft(null, weight, null));
	}

	@Test
	void rejectsNullWeight() {
		Name name = Name.of("Alice");

		assertThrows(NullPointerException.class, () -> new ShareholderDraft(name, null, null));
	}

	@Test
	void acceptsNullUserId() {
		ShareholderDraft draft = new ShareholderDraft(Name.of("Alice"), Weight.of(new Fraction(1, 2)), null);

		assertNull(draft.userId());
	}
}
