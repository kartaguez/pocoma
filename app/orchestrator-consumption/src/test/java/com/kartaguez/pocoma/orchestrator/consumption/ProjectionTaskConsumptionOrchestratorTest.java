package com.kartaguez.pocoma.orchestrator.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionPublicationResult;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.projection.task.ProjectionPreparationOutcome;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionBudgetLimit;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationCounters;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

class ProjectionTaskConsumptionOrchestratorTest {
	private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
	private static final ProjectionType TYPE = new ProjectionType("READ_POT");
	private static final WorkerId WORKER = new WorkerId("projection-worker");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));

	@Test
	void refreshesCandidatesAfterFirstAcquiredWhileTraversingBusyAndNotReadyInTheCurrentPage() {
		var busy = candidate("busy", 1, NOW);
		var notReady = candidate("not-ready", 2, NOW.plusSeconds(1));
		var first = candidate("first", 3, NOW.plusSeconds(2));
		var second = candidate("second", 4, NOW.plusSeconds(3));
		var events = new ArrayList<String>();
		var store = new RecordingStore(List.of(busy, notReady, first, second), second, events);
		var acquisitionResults = new java.util.ArrayDeque<AcquireResult>(List.of(
				new AcquireResult.Busy(NOW.plusSeconds(20)),
				new AcquireResult.NotReady(NOW.plusSeconds(10)),
				new AcquireResult.Acquired(claim()),
				new AcquireResult.Acquired(claim())));
		var service = service(events);
		var orchestrator = new ProjectionTaskConsumptionOrchestrator(
				Set.of(TYPE), 0, 1, store,
				input -> {
					String id = input.consumptionKey().consumable().components().get(2);
					events.add("acquire:" + id);
					return acquisitionResults.remove();
				}, service);

		var result = assertInstanceOf(ConsumptionOrchestrationResult.BudgetExhausted.class,
				orchestrator.run(new ConsumptionOrchestrationInput(
						WORKER, LEASE, new ConsumptionOrchestrationBudget(10, 2))));

		assertEquals(ConsumptionBudgetLimit.EXECUTIONS, result.limit());
		assertEquals(new ConsumptionOrchestrationCounters(4, 2), result.counters());
		assertEquals(Optional.of(NOW.plusSeconds(10)), result.nextKnownEligibility());
		assertEquals(2, store.findCalls);
		assertEquals(Optional.empty(), store.cursors.getFirst());
		assertEquals(Optional.of(first.rowId()), store.cursors.get(1));
		assertEquals(List.of(
				"find",
				"acquire:busy", "acquire:not-ready", "acquire:first", "execute:first",
				"find",
				"acquire:second", "execute:second"), events);
	}

	private static ProjectionTaskConsumptionService service(List<String> events) {
		var failure = new ProcessingFailure(
				new ProcessingFailureCode("TEMPORARY"), "projection", "retry", NOW);
		return new ProjectionTaskConsumptionService(task -> {
			events.add("execute:" + task.projectionKey().targetObjectId().value());
			return new ProjectionPreparationOutcome.Temporary(failure);
		}, new NoOpProjectionWriter(),
				input -> { throw new AssertionError("finalizer must not be called"); },
				input -> FencedMutationResult.APPLIED,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ProjectionTaskCandidate candidate(String id, long version, Instant createdAt) {
		var key = new ProjectionKey(TYPE, new TargetObjectType("POT"), new TargetObjectId(id), version);
		return new ProjectionTaskCandidate(UUID.randomUUID(), new ProjectionTask(key), createdAt);
	}

	private static Claim claim() {
		return Claim.active(ClaimId.generate(), UUID.randomUUID(), WORKER, 1, NOW, LEASE);
	}

	private static final class RecordingStore implements ProjectionTaskStorePort {
		private final List<ProjectionTaskCandidate> firstPage;
		private final ProjectionTaskCandidate secondPage;
		private final List<String> events;
		private final List<Optional<UUID>> cursors = new ArrayList<>();
		private int findCalls;

		private RecordingStore(List<ProjectionTaskCandidate> firstPage,
				ProjectionTaskCandidate secondPage, List<String> events) {
			this.firstPage = firstPage;
			this.secondPage = secondPage;
			this.events = events;
		}

		@Override
		public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			throw new AssertionError("ensure must not be called");
		}

		@Override
		public List<ProjectionTaskCandidate> findCandidates(
				Set<ProjectionType> projectionTypes, int segmentIndex, int segmentCount,
				Optional<Instant> afterCreatedAt, Optional<UUID> afterRowId, int limit) {
			events.add("find");
			findCalls++;
			cursors.add(afterRowId);
			return findCalls == 1 ? firstPage : List.of(secondPage);
		}
	}

	private static final class NoOpProjectionWriter implements ProjectionWritePort {
		@Override
		public ProjectionPublicationResult publish(ValidatedProjection projection) {
			throw new AssertionError("publish must not be called");
		}

		@Override
		public void recordFailure(ProjectionFailure failure) {
			throw new AssertionError("recordFailure must not be called");
		}
	}
}
