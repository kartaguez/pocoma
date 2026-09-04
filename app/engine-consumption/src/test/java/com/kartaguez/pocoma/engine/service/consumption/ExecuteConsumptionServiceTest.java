package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Rejected;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;

class ExecuteConsumptionServiceTest {

	private static final UUID SLOT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final ClaimId CLAIM_ID = new ClaimId(
			UUID.fromString("20000000-0000-0000-0000-000000000001"));
	private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");

	@Test
	void executesBusinessThenProvenanceThenSuccessFencing() {
		List<String> calls = new ArrayList<>();
		RecordingLifecycle lifecycle = new RecordingLifecycle(calls, true);
		RecordingProvenance provenance = new RecordingProvenance(calls);
		ExecuteConsumptionService service = service(lifecycle, provenance);
		ConsumptionExecutionResult expected = successfulResult(SLOT_ID, new Success());

		ConsumptionExecutionResult actual = service.execute(new ExecuteConsumptionInput(
				SLOT_ID,
				CLAIM_ID,
				context -> {
					calls.add("business");
					assertEquals(SLOT_ID, context.slotId());
					assertEquals(CLAIM_ID, context.claimId());
					return expected;
				}));

		assertSame(expected, actual);
		assertEquals(List.of("business", "inputs", "results", "terminalize"), calls);
		assertEquals(TerminalOutcome.SUCCESS, lifecycle.outcome);
		assertEquals(Optional.empty(), lifecycle.reason);
		assertEquals(NOW, lifecycle.doneAt);
	}

	@Test
	void mapsBusinessRejectionToRejectedTerminalOutcome() {
		RecordingLifecycle lifecycle = new RecordingLifecycle(new ArrayList<>(), true);
		ConsumptionExecutionResult result = successfulResult(SLOT_ID, new Rejected("VERSION_CONFLICT"));

		service(lifecycle, new RecordingProvenance(new ArrayList<>())).execute(
				new ExecuteConsumptionInput(SLOT_ID, CLAIM_ID, ignored -> result));

		assertEquals(TerminalOutcome.REJECTED, lifecycle.outcome);
		assertEquals(Optional.of(new TerminalReason("VERSION_CONFLICT")), lifecycle.reason);
		assertEquals("VERSION_CONFLICT", ((Rejected) result.outcome()).rejectionCode());
	}

	@Test
	void failedFencingThrowsAfterProvenanceWasRequested() {
		List<String> calls = new ArrayList<>();
		RecordingLifecycle lifecycle = new RecordingLifecycle(calls, false);
		LostClaimException failure = assertThrows(LostClaimException.class, () ->
				service(lifecycle, new RecordingProvenance(calls)).execute(new ExecuteConsumptionInput(
						SLOT_ID, CLAIM_ID, ignored -> successfulResult(SLOT_ID, new Success()))));

		assertEquals(SLOT_ID, failure.slotId());
		assertEquals(CLAIM_ID, failure.claimId());
		assertEquals(List.of("inputs", "results", "terminalize"), calls);
	}

	@Test
	void rejectsCrossSlotProvenanceBeforeWritingAnything() {
		UUID anotherSlot = UUID.randomUUID();
		List<String> calls = new ArrayList<>();
		RecordingLifecycle lifecycle = new RecordingLifecycle(calls, true);
		RecordingProvenance provenance = new RecordingProvenance(calls);
		ConsumptionExecutionResult wrongInput = new ConsumptionExecutionResult(
				new Success(), List.of(input(anotherSlot)), List.of(result(SLOT_ID)));
		ConsumptionExecutionResult wrongResult = new ConsumptionExecutionResult(
				new Success(), List.of(input(SLOT_ID)), List.of(result(anotherSlot)));

		assertThrows(IllegalArgumentException.class, () -> service(lifecycle, provenance).execute(
				new ExecuteConsumptionInput(SLOT_ID, CLAIM_ID, ignored -> wrongInput)));
		assertThrows(IllegalArgumentException.class, () -> service(lifecycle, provenance).execute(
				new ExecuteConsumptionInput(SLOT_ID, CLAIM_ID, ignored -> wrongResult)));
		assertEquals(List.of(), calls);
	}

	@Test
	void resultOwnsDefensiveCopiesAndRejectionCodeIsValidated() {
		List<ConsumptionInput> inputs = new ArrayList<>(List.of(input(SLOT_ID)));
		List<ConsumptionResult> results = new ArrayList<>(List.of(result(SLOT_ID)));
		ConsumptionExecutionResult executionResult = new ConsumptionExecutionResult(new Success(), inputs, results);

		inputs.clear();
		results.clear();

		assertEquals(1, executionResult.inputs().size());
		assertEquals(1, executionResult.results().size());
		assertThrows(UnsupportedOperationException.class, () -> executionResult.inputs().clear());
		assertThrows(NullPointerException.class, () -> new Rejected(null));
		assertThrows(IllegalArgumentException.class, () -> new Rejected("  "));
	}

	private static ExecuteConsumptionService service(
			ConsumptionLifecyclePersistencePort lifecycle,
			ConsumptionProvenancePersistencePort provenance) {
		return new ExecuteConsumptionService(lifecycle, provenance, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ConsumptionExecutionResult successfulResult(
			UUID slotId, com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome outcome) {
		return new ConsumptionExecutionResult(outcome, List.of(input(slotId)), List.of(result(slotId)));
	}

	private static ConsumptionInput input(UUID slotId) {
		return new ConsumptionInput(slotId, "POT", "pot-1", 7);
	}

	private static ConsumptionResult result(UUID slotId) {
		return new ConsumptionResult(
				slotId, "EVENT", "POT_UPDATED", "event-1", OptionalLong.empty(),
				Optional.of("POT"), Optional.of("pot-1"), OptionalLong.of(8), NOW);
	}

	private static final class RecordingLifecycle implements ConsumptionLifecyclePersistencePort {
		private final List<String> calls;
		private final boolean fencingResult;
		private TerminalOutcome outcome;
		private Optional<TerminalReason> reason;
		private Instant doneAt;

		private RecordingLifecycle(List<String> calls, boolean fencingResult) {
			this.calls = calls;
			this.fencingResult = fencingResult;
		}

		@Override
		public boolean tryTerminalize(
				UUID slotId, ClaimId claimId, TerminalOutcome terminalOutcome,
				Optional<TerminalReason> terminalReason, Instant terminalizedAt) {
			calls.add("terminalize");
			assertEquals(SLOT_ID, slotId);
			assertEquals(CLAIM_ID, claimId);
			outcome = terminalOutcome;
			reason = terminalReason;
			doneAt = terminalizedAt;
			return fencingResult;
		}

		@Override
		public AcquireResult acquire(
				ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now) {
			throw new UnsupportedOperationException();
		}

		@Override
		public FencedMutationResult handleFailure(
				UUID slotId, ClaimId claimId, ProcessingFailure failure,
				FailureDecision decision, Instant now) {
			throw new UnsupportedOperationException();
		}

		@Override
		public AbandonResult abandon(UUID slotId, TerminalReason reason, Instant now) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class RecordingProvenance implements ConsumptionProvenancePersistencePort {
		private final List<String> calls;

		private RecordingProvenance(List<String> calls) {
			this.calls = calls;
		}

		@Override
		public void appendInputs(List<ConsumptionInput> inputs) {
			calls.add("inputs");
		}

		@Override
		public void appendResults(List<ConsumptionResult> results) {
			calls.add("results");
		}

		@Override
		public List<ConsumptionInput> findInputs(UUID slotId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ConsumptionResult> findResults(UUID slotId) {
			throw new UnsupportedOperationException();
		}
	}
}
