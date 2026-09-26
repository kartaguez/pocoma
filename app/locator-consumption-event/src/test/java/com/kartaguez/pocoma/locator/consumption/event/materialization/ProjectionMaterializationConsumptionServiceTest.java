package com.kartaguez.pocoma.locator.consumption.event.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;

class ProjectionMaterializationConsumptionServiceTest {
	private static final Instant RECORDED_AT = Instant.parse("2026-09-26T10:00:00Z");
	private static final ProjectionMaterializationCandidate CANDIDATE = new ProjectionMaterializationCandidate(
			UUID.fromString("10000000-0000-0000-0000-000000000001"), new EventType("POT_CREATED"),
			new ProjectionType("READ_POT"), new TargetObjectType("POT"),
			new TargetObjectId("20000000-0000-0000-0000-000000000001"), 42, RECORDED_AT);
	private static final ProjectionKey KEY = new ProjectionKey(
			CANDIDATE.projectionType(), CANDIDATE.targetObjectType(),
			CANDIDATE.targetObjectId(), CANDIDATE.targetVersion());
	private static final Claim CLAIM = Claim.active(
			new ClaimId(UUID.fromString("30000000-0000-0000-0000-000000000001")),
			UUID.fromString("40000000-0000-0000-0000-000000000001"), new WorkerId("worker"), 1,
			RECORDED_AT, new ClaimLease(Duration.ofSeconds(30)));

	@Test
	void suppliesTheExactClaimKeyAndTimestampInsideTheFencedEffect() {
		var tasks = new RecordingTasks();
		var finalizer = new ApplyingFinalizer();

		var result = new ProjectionMaterializationConsumptionService(finalizer, tasks)
				.finalize(CANDIDATE, CLAIM);

		assertEquals(FencedMutationResult.APPLIED, result);
		assertEquals(CLAIM.slotId(), finalizer.input.slotId());
		assertEquals(CLAIM.claimId(), finalizer.input.claimId());
		assertEquals(KEY, tasks.key);
		assertEquals(RECORDED_AT, tasks.createdAt);
		assertEquals(1, tasks.calls.get());
	}

	@Test
	void lostClaimNeverInvokesEnsure() {
		var tasks = new RecordingTasks();
		FinalizeConsumptionUseCase stale = input -> FencedMutationResult.LOST_CLAIM;

		assertEquals(FencedMutationResult.LOST_CLAIM,
				new ProjectionMaterializationConsumptionService(stale, tasks).finalize(CANDIDATE, CLAIM));
		assertEquals(0, tasks.calls.get());
	}

	@Test
	void ensureFailureIsPropagatedUnchanged() {
		RuntimeException failure = new IllegalStateException("ensure failed");
		ProjectionTaskStorePort tasks = new RecordingTasks() {
			@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) { throw failure; }
		};

		RuntimeException actual = assertThrows(RuntimeException.class,
				() -> new ProjectionMaterializationConsumptionService(new ApplyingFinalizer(), tasks)
						.finalize(CANDIDATE, CLAIM));
		assertSame(failure, actual);
	}

	private static final class ApplyingFinalizer implements FinalizeConsumptionUseCase {
		private FinalizeConsumptionInput input;
		@Override public FencedMutationResult finalizeConsumption(FinalizeConsumptionInput value) {
			input = value;
			value.durableEffect().apply();
			return FencedMutationResult.APPLIED;
		}
	}

	private static class RecordingTasks implements ProjectionTaskStorePort {
		private final AtomicInteger calls = new AtomicInteger();
		private ProjectionKey key;
		private Instant createdAt;
		@Override public ProjectionTask ensure(ProjectionKey value, Instant instant) {
			calls.incrementAndGet(); key = value; createdAt = instant; return new ProjectionTask(value);
		}
		@Override public List<ProjectionTaskCandidate> findCandidates(Set<ProjectionType> projectionTypes,
				int segmentIndex, int segmentCount, Optional<Instant> afterCreatedAt,
				Optional<UUID> afterRowId, int limit) {
			throw new UnsupportedOperationException();
		}
	}
}
