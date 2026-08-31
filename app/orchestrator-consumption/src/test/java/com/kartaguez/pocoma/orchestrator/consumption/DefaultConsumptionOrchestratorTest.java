package com.kartaguez.pocoma.orchestrator.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;

class DefaultConsumptionOrchestratorTest {
	private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
	private static final WorkerId WORKER = new WorkerId("worker");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));

	@Test
	void scansEligibilityAndRestartsAfterTheSingleAcquiredExecution() {
		var acquireResults = new ArrayDeque<AcquireResult>(List.of(
				new AcquireResult.Busy(NOW.plusSeconds(20)),
				new AcquireResult.NotReady(NOW.plusSeconds(10)),
				new AcquireResult.AlreadyDone(TerminalOutcome.SUCCESS),
				new AcquireResult.Acquired(claim())));
		AtomicInteger opens = new AtomicInteger();
		ConsumptionLocator locator = () -> search(opens.incrementAndGet() == 1 ? 4 : 0);
		AtomicInteger executions = new AtomicInteger();
		var orchestrator = new DefaultConsumptionOrchestrator(locator, input -> acquireResults.remove(), input -> {
			executions.incrementAndGet();
			return success();
		}, input -> FencedMutationResult.APPLIED);

		var result = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(input(10, 10)));

		assertEquals(2, opens.get());
		assertEquals(1, executions.get());
		assertEquals(new ConsumptionOrchestrationCounters(4, 1), result.counters());
		assertEquals(Optional.of(NOW.plusSeconds(10)), result.nextKnownEligibility());
	}

	@Test
	void lostClaimIsNeverClassifiedOrHandled() {
		AtomicInteger classifications = new AtomicInteger();
		AtomicInteger failures = new AtomicInteger();
		AtomicInteger opens = new AtomicInteger();
		ConsumptionLocator locator = () -> opens.incrementAndGet() == 1
				? single(new LocatedConsumption(key(1), context -> success(), failure -> {
					classifications.incrementAndGet(); throw new AssertionError();
				})) : search(0);
		var claimed = claim();
		var orchestrator = new DefaultConsumptionOrchestrator(locator,
				input -> new AcquireResult.Acquired(claimed),
				input -> { throw new LostClaimException(input.slotId(), input.claimId()); },
				input -> { failures.incrementAndGet(); return FencedMutationResult.APPLIED; });

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, orchestrator.run(input(3, 3)));
		assertEquals(0, classifications.get());
		assertEquals(0, failures.get());
	}

	@Test
	void technicalFailureIsClassifiedHandledAndThenSearchRestarts() {
		AtomicInteger opens = new AtomicInteger();
		AtomicInteger handled = new AtomicInteger();
		var located = new LocatedConsumption(key(1), context -> success(),
				failure -> new ProcessingFailure("TEST", "failed", NOW));
		var orchestrator = new DefaultConsumptionOrchestrator(
				() -> opens.incrementAndGet() == 1 ? single(located) : search(0),
				input -> new AcquireResult.Acquired(claim()),
				input -> { throw new IllegalStateException("boom"); },
				input -> { handled.incrementAndGet(); return FencedMutationResult.LOST_CLAIM; });

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, orchestrator.run(input(3, 3)));
		assertEquals(1, handled.get());
		assertEquals(2, opens.get());
	}

	@Test
	void candidateBudgetStopsWithoutReadingAnExtraCandidate() {
		AtomicInteger reads = new AtomicInteger();
		ConsumptionSearch search = new ConsumptionSearch() {
			@Override public Optional<LocatedConsumption> next() {
				reads.incrementAndGet(); return Optional.of(located(reads.get()));
			}
		};
		var orchestrator = new DefaultConsumptionOrchestrator(() -> search,
				input -> new AcquireResult.AlreadyDone(TerminalOutcome.SUCCESS), input -> success(),
				input -> FencedMutationResult.APPLIED);
		var result = assertInstanceOf(ConsumptionOrchestrationResult.BudgetExhausted.class,
				orchestrator.run(input(2, 5)));
		assertEquals(BudgetLimit.CANDIDATES, result.limit());
		assertEquals(2, reads.get());
	}

	private static ConsumptionOrchestrationInput input(int candidates, int executions) {
		return new ConsumptionOrchestrationInput(WORKER, LEASE,
				new ConsumptionOrchestrationBudget(candidates, executions));
	}
	private static ConsumptionSearch search(int count) {
		var values = new ArrayDeque<LocatedConsumption>();
		for (int index = 0; index < count; index++) values.add(located(index));
		return new ConsumptionSearch() {
			@Override public Optional<LocatedConsumption> next() { return Optional.ofNullable(values.poll()); }
		};
	}
	private static ConsumptionSearch single(LocatedConsumption value) {
		var values = new ArrayDeque<>(List.of(value));
		return new ConsumptionSearch() {
			@Override public Optional<LocatedConsumption> next() { return Optional.ofNullable(values.poll()); }
		};
	}
	private static LocatedConsumption located(int index) {
		return new LocatedConsumption(key(index), context -> success(),
				failure -> new ProcessingFailure("TEST", "failed", NOW));
	}
	private static ConsumptionKey key(int index) {
		return new ConsumptionKey(new ConsumableIdentity("TEST", List.of(Integer.toString(index))),
				new ConsumerIdentity("TEST", List.of()));
	}
	private static Claim claim() {
		return Claim.active(new ClaimId(UUID.randomUUID()), UUID.randomUUID(), WORKER, 1, NOW, LEASE);
	}
	private static ConsumptionExecutionResult success() {
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), List.of(), List.of());
	}
}
