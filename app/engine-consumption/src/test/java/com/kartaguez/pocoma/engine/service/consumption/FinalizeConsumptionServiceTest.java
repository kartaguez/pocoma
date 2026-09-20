package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;

class FinalizeConsumptionServiceTest {
	private static final UUID SLOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final ClaimId CLAIM_ID = new ClaimId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
	private static final Instant NOW = Instant.parse("2026-09-20T08:00:00Z");

	@Test
	void locksAndFencesBeforeApplyingTheDurableEffect() {
		List<String> calls = new ArrayList<>();
		var lifecycle = new RecordingLifecycle(calls, true, true);

		var result = service(lifecycle).finalizeConsumption(new FinalizeConsumptionInput(
				SLOT_ID, CLAIM_ID, new Success(), () -> calls.add("effect")));

		assertEquals(FencedMutationResult.APPLIED, result);
		assertEquals(List.of("lock", "effect", "terminalize"), calls);
	}

	@Test
	void staleClaimCannotApplyItsDurableEffect() {
		List<String> calls = new ArrayList<>();

		var result = service(new RecordingLifecycle(calls, false, true)).finalizeConsumption(
				new FinalizeConsumptionInput(SLOT_ID, CLAIM_ID, new Success(), () -> calls.add("effect")));

		assertEquals(FencedMutationResult.LOST_CLAIM, result);
		assertEquals(List.of("lock"), calls);
	}

	@Test
	void terminalFailureAppliesItsEffectBetweenTheFenceAndSlotTermination() {
		List<String> calls = new ArrayList<>();
		var lifecycle = new RecordingLifecycle(calls, true, true);
		var failure = failure();

		var result = service(lifecycle).finalizeConsumption(new FinalizeConsumptionInput(
				SLOT_ID, CLAIM_ID, new TerminalFailure(failure), () -> calls.add("recordFailure")));

		assertEquals(FencedMutationResult.APPLIED, result);
		assertEquals(List.of("lock", "recordFailure", "fail"), calls);
		assertEquals(failure, lifecycle.failure);
	}

	@Test
	void anImpossiblePostLockFenceLossFailsTheTransaction() {
		List<String> calls = new ArrayList<>();
		var lifecycle = new RecordingLifecycle(calls, true, false);

		assertThrows(IllegalStateException.class, () -> service(lifecycle).finalizeConsumption(
				new FinalizeConsumptionInput(SLOT_ID, CLAIM_ID, new Success(), () -> calls.add("effect"))));
		assertEquals(List.of("lock", "effect", "terminalize"), calls);
	}

	private static FinalizeConsumptionService service(ConsumptionLifecyclePersistencePort lifecycle) {
		return new FinalizeConsumptionService(lifecycle, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ProcessingFailure failure() {
		return new ProcessingFailure(new ProcessingFailureCode("IMPOSSIBLE"), "projection", "cannot project", NOW);
	}

	private static final class RecordingLifecycle implements ConsumptionLifecyclePersistencePort {
		private final List<String> calls;
		private final boolean ownsClaim;
		private final boolean mutationSucceeds;
		private ProcessingFailure failure;

		private RecordingLifecycle(List<String> calls, boolean ownsClaim, boolean mutationSucceeds) {
			this.calls = calls;
			this.ownsClaim = ownsClaim;
			this.mutationSucceeds = mutationSucceeds;
		}

		@Override public boolean lockCurrentClaim(UUID slotId, ClaimId claimId) {
			calls.add("lock");
			return ownsClaim;
		}
		@Override public boolean tryTerminalize(UUID slotId, ClaimId claimId, TerminalOutcome outcome,
				Optional<TerminalReason> reason, Instant doneAt) {
			calls.add("terminalize");
			return mutationSucceeds;
		}
		@Override public FencedMutationResult handleFailure(UUID slotId, ClaimId claimId,
				ProcessingFailure value, FailureDecision decision, Instant now) {
			calls.add("fail");
			failure = value;
			return mutationSucceeds ? FencedMutationResult.APPLIED : FencedMutationResult.LOST_CLAIM;
		}
		@Override public AcquireResult acquire(ConsumptionKey key, ClaimId claimId, WorkerId workerId,
				ClaimLease lease, Instant now) { throw new UnsupportedOperationException(); }
		@Override public AbandonResult abandon(UUID slotId, TerminalReason reason, Instant now) {
			throw new UnsupportedOperationException();
		}
	}
}
