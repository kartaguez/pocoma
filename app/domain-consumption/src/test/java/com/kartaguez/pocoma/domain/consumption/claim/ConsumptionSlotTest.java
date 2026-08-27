package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.CommandConsumptionKey;

class ConsumptionSlotTest {

	private static final CommandConsumptionKey KEY = new CommandConsumptionKey(UUID.randomUUID());

	@Test
	void advancesRevisionWheneverOwnershipChanges() {
		ConsumptionSlot initial = ConsumptionSlot.initial(KEY);
		ClaimId claimId = ClaimId.generate();

		ConsumptionSlot acquired = initial.acquiredBy(claimId);
		ConsumptionSlot released = acquired.withoutCurrentClaim();

		assertEquals(0, initial.revision());
		assertEquals(Optional.of(claimId), acquired.currentClaimId());
		assertEquals(1, acquired.revision());
		assertEquals(Optional.empty(), released.currentClaimId());
		assertEquals(2, released.revision());
	}

	@Test
	void rejectsNegativeRevision() {
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(KEY, -1, Optional.empty()));
	}
}
