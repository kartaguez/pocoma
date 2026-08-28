package com.kartaguez.pocoma.domain.pot.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class ShareholderWeightTest {

	@Test
	void createsShareholderWeight() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		Weight weight = Weight.of(new Fraction(1, 2));

		ShareholderWeight shareholderWeight = new ShareholderWeight(shareholderId, weight);

		assertEquals(shareholderId, shareholderWeight.shareholderId());
		assertEquals(weight, shareholderWeight.weight());
	}

	@Test
	void rejectsNullShareholderId() {
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> new ShareholderWeight(null, weight));
	}

	@Test
	void rejectsNullWeight() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new ShareholderWeight(shareholderId, null));
	}
}
