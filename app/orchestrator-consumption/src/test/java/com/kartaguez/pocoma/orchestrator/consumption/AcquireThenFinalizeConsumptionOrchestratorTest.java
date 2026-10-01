package com.kartaguez.pocoma.orchestrator.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.orchestrator.consumption.fenced.FencedConsumptionCandidateSearch;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

class AcquireThenFinalizeConsumptionOrchestratorTest {
	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	private static final WorkerId WORKER = new WorkerId("worker");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));

	@Test
	void emptySearchIsIdleAndClosed() {
		AtomicBoolean closed = new AtomicBoolean();
		var orchestrator = new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> search(List.of(), closed), AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> { throw new AssertionError("acquire must not be called"); },
				(candidate, claim) -> { throw new AssertionError("finalizer must not be called"); });

		var result = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(input(5, 5)));

		assertEquals(new ConsumptionOrchestrationCounters(0, 0), result.counters());
		assertTrue(closed.get());
	}

	@Test
	void scansPagesAndPreservesTheExactCandidateThroughAcquisitionAndFinalization() {
		var first = new Candidate("first");
		var second = new Candidate("second");
		var pages = new ArrayDeque<List<Candidate>>(List.of(List.of(first), List.of(second), List.of()));
		var acquiredKeys = new ArrayList<ConsumptionKey>();
		var finalized = new ArrayList<Candidate>();
		Claim claim = claim();
		var orchestrator = new AcquireThenFinalizeConsumptionOrchestrator<>(
				() -> limit -> pages.remove(), candidate -> key(candidate.value()), input -> {
					acquiredKeys.add(input.consumptionKey());
					return new AcquireResult.Acquired(claim);
				}, (candidate, actualClaim) -> {
					assertSame(claim, actualClaim);
					finalized.add(candidate);
					return FencedMutationResult.APPLIED;
				});

		var result = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(input(5, 5)));

		assertEquals(List.of(key("first"), key("second")), acquiredKeys);
		assertEquals(List.of(first, second), finalized);
		assertEquals(new ConsumptionOrchestrationCounters(2, 2), result.counters());
	}

	@Test
	void finalizesMultipleAcquiredCandidatesFromTheSamePageByDefault() {
		AtomicInteger pageCalls = new AtomicInteger();
		var finalized = new ArrayList<String>();
		var orchestrator = new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> limit -> pageCalls.getAndIncrement() == 0 ? List.of("first", "second") : List.of(),
				AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> new AcquireResult.Acquired(claim()),
				(candidate, claim) -> {
					finalized.add(candidate);
					return FencedMutationResult.APPLIED;
				});

		var result = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(input(5, 5)));

		assertEquals(List.of("first", "second"), finalized);
		assertEquals(2, pageCalls.get());
		assertEquals(new ConsumptionOrchestrationCounters(2, 2), result.counters());
	}

	@Test
	void busyNotReadyAlreadyDoneAndNotEligibleContinueWithoutFinalization() {
		var results = new ArrayDeque<AcquireResult>(List.of(
				new AcquireResult.Busy(NOW.plusSeconds(20)),
				new AcquireResult.NotReady(NOW.plusSeconds(10)),
				new AcquireResult.AlreadyDone(TerminalOutcome.SUCCESS, Optional.empty()),
				new AcquireResult.NotEligible(),
				new AcquireResult.Acquired(claim())));
		AtomicInteger finalized = new AtomicInteger();
		var orchestrator = new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> paged(List.of("busy", "not-ready", "done", "ineligible", "winner")),
				AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> results.remove(),
				(candidate, claim) -> {
					finalized.incrementAndGet();
					return FencedMutationResult.LOST_CLAIM;
				});

		var result = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(input(10, 10)));

		assertEquals(1, finalized.get());
		assertEquals(Optional.of(NOW.plusSeconds(10)), result.nextKnownEligibility());
		assertEquals(new ConsumptionOrchestrationCounters(5, 1), result.counters());
	}

	@Test
	void candidateAndExecutionBudgetsStopWithoutRequestingAnotherPage() {
		AtomicInteger pages = new AtomicInteger();
		var candidateLimited = new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> limit -> {
					pages.incrementAndGet();
					assertEquals(2, limit);
					return List.of("one", "two");
				}, AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> new AcquireResult.AlreadyDone(TerminalOutcome.SUCCESS, Optional.empty()),
				(candidate, claim) -> FencedMutationResult.APPLIED);

		var byCandidates = assertInstanceOf(ConsumptionOrchestrationResult.BudgetExhausted.class,
				candidateLimited.run(input(2, 5)));
		assertEquals(ConsumptionBudgetLimit.CANDIDATES, byCandidates.limit());
		assertEquals(1, pages.get());

		var executionLimited = new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> paged(List.of("one", "two")), AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> new AcquireResult.Acquired(claim()),
				(candidate, claim) -> FencedMutationResult.APPLIED);
		var byExecutions = assertInstanceOf(ConsumptionOrchestrationResult.BudgetExhausted.class,
				executionLimited.run(input(5, 1)));
		assertEquals(ConsumptionBudgetLimit.EXECUTIONS, byExecutions.limit());
		assertEquals(new ConsumptionOrchestrationCounters(1, 1), byExecutions.counters());
	}

	@Test
	void runtimeFailuresFromEveryBoundaryAreReturnedAndSearchIsClosed() {
		RuntimeException sourceFailure = new IllegalStateException("source");
		assertFailure(new AcquireThenFinalizeConsumptionOrchestrator<String>(
				() -> { throw sourceFailure; }, AcquireThenFinalizeConsumptionOrchestratorTest::key,
				input -> new AcquireResult.Acquired(claim()),
				(candidate, claim) -> FencedMutationResult.APPLIED), sourceFailure);

		assertBoundaryFailure("page", (search, failure) -> search.pageFailure = failure);
		assertBoundaryFailure("inspection", (search, failure) -> search.inspectionFailure = failure);
		assertBoundaryFailure("key", (search, failure) -> search.keyFailure = failure);
		assertBoundaryFailure("acquire", (search, failure) -> search.acquireFailure = failure);
		assertBoundaryFailure("finalize", (search, failure) -> search.finalizeFailure = failure);
		assertBoundaryFailure("close", (search, failure) -> search.closeFailure = failure);
	}

	private static void assertBoundaryFailure(String name, FailureSetter setter) {
		RuntimeException failure = new IllegalStateException(name);
		FailingBoundaries boundaries = new FailingBoundaries();
		setter.set(boundaries, failure);
		var orchestrator = new AcquireThenFinalizeConsumptionOrchestrator<String>(boundaries::search,
				candidate -> {
					if (boundaries.keyFailure != null) throw boundaries.keyFailure;
					return key(candidate);
				}, input -> {
					if (boundaries.acquireFailure != null) throw boundaries.acquireFailure;
					return new AcquireResult.Acquired(claim());
				}, (candidate, claim) -> {
					if (boundaries.finalizeFailure != null) throw boundaries.finalizeFailure;
					return FencedMutationResult.APPLIED;
				});
		assertFailure(orchestrator, failure);
		assertTrue(boundaries.closed.get());
	}

	private static void assertFailure(ConsumptionOrchestrator orchestrator, RuntimeException expected) {
		var result = assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,
				orchestrator.run(input(2, 2)));
		assertSame(expected, result.cause());
	}

	private static FencedConsumptionCandidateSearch<String> paged(List<String> candidates) {
		var pages = new ArrayDeque<List<String>>(List.of(candidates, List.of()));
		return limit -> pages.remove();
	}

	private static <C> FencedConsumptionCandidateSearch<C> search(List<C> candidates, AtomicBoolean closed) {
		var pages = new ArrayDeque<List<C>>(List.of(candidates, List.of()));
		return new FencedConsumptionCandidateSearch<>() {
			@Override public List<C> nextPage(int limit) { return pages.remove(); }
			@Override public void close() { closed.set(true); }
		};
	}

	private static ConsumptionOrchestrationInput input(int candidates, int executions) {
		return new ConsumptionOrchestrationInput(WORKER, LEASE,
				new ConsumptionOrchestrationBudget(candidates, executions));
	}

	private static ConsumptionKey key(String value) {
		return new ConsumptionKey(new ConsumableIdentity("TEST", List.of(value)),
				new ConsumerIdentity("FINALIZER", List.of()));
	}

	private static Claim claim() {
		return Claim.active(ClaimId.generate(), UUID.randomUUID(), WORKER, 1, NOW, LEASE);
	}

	private record Candidate(String value) {}

	@FunctionalInterface
	private interface FailureSetter {
		void set(FailingBoundaries boundaries, RuntimeException failure);
	}

	private static final class FailingBoundaries {
		private final AtomicBoolean closed = new AtomicBoolean();
		private RuntimeException pageFailure;
		private RuntimeException inspectionFailure;
		private RuntimeException keyFailure;
		private RuntimeException acquireFailure;
		private RuntimeException finalizeFailure;
		private RuntimeException closeFailure;

		FencedConsumptionCandidateSearch<String> search() {
			return new FencedConsumptionCandidateSearch<>() {
				private boolean returned;
				@Override public List<String> nextPage(int limit) {
					if (pageFailure != null) throw pageFailure;
					if (returned) return List.of();
					returned = true;
					return List.of("candidate");
				}
				@Override public void candidateInspected(String candidate) {
					if (inspectionFailure != null) throw inspectionFailure;
				}
				@Override public void close() {
					closed.set(true);
					if (closeFailure != null) throw closeFailure;
				}
			};
		}
	}
}
