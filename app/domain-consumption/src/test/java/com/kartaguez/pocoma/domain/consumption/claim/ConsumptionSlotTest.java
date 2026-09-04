package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;

class ConsumptionSlotTest {

	private static final UUID SLOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
	private static final ConsumptionKey KEY = new ConsumptionKey("work", List.of("42"));
	private static final TerminalReason REASON = new TerminalReason("BUSINESS_CONFLICT");

	@Test
	void claimsReleasesAndCompletesWithMonotoneRevisions() {
		ClaimId claimId = ClaimId.generate();
		ConsumptionSlot initial = ConsumptionSlot.initial(SLOT_ID, KEY, NOW);
		ConsumptionSlot claimed = initial.withCurrentClaim(claimId);
		ConsumptionSlot released = claimed.releaseCurrentClaim(claimId, NOW.plusSeconds(5));
		ConsumptionSlot completed = claimed.complete(
				claimId, TerminalOutcome.SUCCESS, Optional.empty(), NOW.plusSeconds(2));
		assertEquals(ConsumptionStatus.PENDING, initial.status());
		assertEquals(Optional.of(claimId), claimed.currentClaimId());
		assertEquals(1, claimed.revision());
		assertTrue(released.currentClaimId().isEmpty());
		assertEquals(NOW.plusSeconds(5), released.nextClaimAt());
		assertEquals(2, released.revision());
		assertEquals(ConsumptionStatus.DONE, completed.status());
		assertEquals(Optional.of(TerminalOutcome.SUCCESS), completed.terminalOutcome());
		assertEquals(Optional.empty(), completed.terminalReason());
		assertEquals(Optional.of(NOW.plusSeconds(2)), completed.doneAt());
	}

	@Test
	void abandonmentFencesTheCurrentClaimAndDoneIsIrreversible() {
		ConsumptionSlot abandoned = ConsumptionSlot.initial(SLOT_ID, KEY, NOW)
				.withCurrentClaim(ClaimId.generate()).abandon(REASON, NOW.plusSeconds(1));
		assertEquals(ConsumptionStatus.DONE, abandoned.status());
		assertEquals(Optional.of(TerminalOutcome.ABANDONED), abandoned.terminalOutcome());
		assertEquals(Optional.of(REASON), abandoned.terminalReason());
		assertTrue(abandoned.currentClaimId().isEmpty());
		assertThrows(IllegalStateException.class, () -> abandoned.withCurrentClaim(ClaimId.generate()));
	}

	@Test
	void rejectsInconsistentStatesAndStaleClaims() {
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(
				SLOT_ID, KEY, -1, ConsumptionStatus.PENDING, Optional.empty(), Optional.empty(), Optional.empty(),
				NOW, NOW, Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(
				SLOT_ID, KEY, 0, ConsumptionStatus.DONE, Optional.empty(), Optional.empty(), Optional.empty(),
				NOW, NOW, Optional.of(NOW)));
		ConsumptionSlot claimed = ConsumptionSlot.initial(SLOT_ID, KEY, NOW).withCurrentClaim(ClaimId.generate());
		assertThrows(IllegalStateException.class,
				() -> claimed.complete(
						ClaimId.generate(), TerminalOutcome.SUCCESS, Optional.empty(), NOW.plusSeconds(1)));
		assertThrows(IllegalArgumentException.class,
				() -> claimed.complete(claimed.currentClaimId().orElseThrow(), TerminalOutcome.ABANDONED,
						Optional.of(REASON), NOW.plusSeconds(1)));
	}

	@Test
	void enforcesTheTerminalOutcomeReasonMatrix() {
		ClaimId claimId = ClaimId.generate();
		ConsumptionSlot claimed = ConsumptionSlot.initial(SLOT_ID, KEY, NOW).withCurrentClaim(claimId);

		ConsumptionSlot rejected = claimed.complete(
				claimId, TerminalOutcome.REJECTED, Optional.of(REASON), NOW.plusSeconds(1));
		assertEquals(Optional.of(REASON), rejected.terminalReason());
		ConsumptionSlot failed = new ConsumptionSlot(
				SLOT_ID, KEY, 2, ConsumptionStatus.DONE, Optional.of(TerminalOutcome.FAILED), Optional.of(REASON),
				Optional.empty(), NOW, NOW, Optional.of(NOW.plusSeconds(1)));
		assertEquals(Optional.of(REASON), failed.terminalReason());

		assertThrows(IllegalArgumentException.class, () -> claimed.complete(
				claimId, TerminalOutcome.SUCCESS, Optional.of(REASON), NOW.plusSeconds(1)));
		assertThrows(IllegalArgumentException.class, () -> claimed.complete(
				claimId, TerminalOutcome.REJECTED, Optional.empty(), NOW.plusSeconds(1)));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(
				SLOT_ID, KEY, 2, ConsumptionStatus.DONE, Optional.of(TerminalOutcome.FAILED), Optional.empty(),
				Optional.empty(), NOW, NOW, Optional.of(NOW.plusSeconds(1))));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(
				SLOT_ID, KEY, 2, ConsumptionStatus.DONE, Optional.of(TerminalOutcome.ABANDONED), Optional.empty(),
				Optional.empty(), NOW, NOW, Optional.of(NOW.plusSeconds(1))));
		assertThrows(IllegalArgumentException.class, () -> new ConsumptionSlot(
				SLOT_ID, KEY, 0, ConsumptionStatus.PENDING, Optional.empty(), Optional.of(REASON),
				Optional.empty(), NOW, NOW, Optional.empty()));
	}
}
