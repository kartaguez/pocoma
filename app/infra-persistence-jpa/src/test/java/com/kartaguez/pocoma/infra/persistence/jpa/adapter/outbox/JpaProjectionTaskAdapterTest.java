package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.legacy.event.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.PotPartitioner;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaProjectionTaskRepository;

@DataJpaTest
@Import(JpaProjectionTaskAdapter.class)
class JpaProjectionTaskAdapterTest {

	@Autowired
	private JpaProjectionTaskAdapter adapter;

	@Autowired
	private JpaProjectionTaskRepository repository;

	@Test
	void coalescesPendingTasksToHighestVersion() {
		PotId potId = PotId.of(UUID.randomUUID());

		adapter.upsertComputeBalancesTask(event(potId, 2));
		adapter.upsertComputeBalancesTask(event(potId, 5));

		ProjectionTaskClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-1").getFirst();
		assertEquals(5, claim.task().targetVersion());
		assertEquals(1, repository.count());
	}

	@Test
	void doesNotCoalesceRunningTask() {
		PotId potId = PotId.of(UUID.randomUUID());

		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim runningClaim = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-1").getFirst();
		assertTrue(adapter.markAccepted(runningClaim.task().id(), runningClaim.claimToken()));
		assertTrue(adapter.markRunning(runningClaim.task().id(), runningClaim.claimToken()));

		var newTask = adapter.upsertComputeBalancesTask(event(potId, 5));

		assertNotEquals(runningClaim.task().id(), newTask.id());
		assertEquals(2, repository.findById(runningClaim.task().id()).orElseThrow().toDescriptor().targetVersion());
		assertEquals(5, newTask.targetVersion());
		assertEquals(2, repository.count());
	}

	@Test
	void createsNewTaskForNewerVersionWhilePreviousTaskIsRunning() {
		PotId potId = PotId.of(UUID.randomUUID());

		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim runningClaim = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-1").getFirst();
		assertTrue(adapter.markRunning(runningClaim.task().id(), runningClaim.claimToken()));

		var newerTask = adapter.upsertComputeBalancesTask(event(potId, 7));

		assertNotEquals(runningClaim.task().id(), newerTask.id());
		assertEquals(7, newerTask.targetVersion());
		assertEquals(2, repository.count());
	}

	@Test
	void expiredLongRunningTaskCanBeReclaimedWithoutHeartbeat() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim claim = adapter.claimPending(10, Duration.ofMillis(20), "fetcher-1").getFirst();
		assertTrue(adapter.markRunning(claim.task().id(), claim.claimToken()));

		sleep(Duration.ofMillis(30));

		ProjectionTaskClaim reclaimed = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-2").getFirst();
		assertEquals(claim.task().id(), reclaimed.task().id());
		assertNotEquals(claim.claimToken(), reclaimed.claimToken());
	}

	@Test
	void heartbeatPreventsReclaimAfterOriginalLeaseWouldHaveExpired() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim claim = adapter.claimPending(10, Duration.ofMillis(20), "fetcher-1").getFirst();
		assertTrue(adapter.markRunning(claim.task().id(), claim.claimToken()));

		sleep(Duration.ofMillis(10));
		assertTrue(adapter.heartbeat(claim.task().id(), claim.claimToken(), Duration.ofMillis(50)));
		sleep(Duration.ofMillis(20));

		assertEquals(0, adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-2").size());
	}

	@Test
	void requiresClaimTokenForLifecycleTransitions() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-1").getFirst();

		assertFalse(adapter.markAccepted(claim.task().id(), UUID.randomUUID()));
		assertTrue(adapter.markAccepted(claim.task().id(), claim.claimToken()));
		assertTrue(adapter.markRunning(claim.task().id(), claim.claimToken()));
		assertFalse(adapter.markDone(claim.task().id(), UUID.randomUUID()));
		assertTrue(adapter.markDone(claim.task().id(), claim.claimToken()));

		assertEquals(0, adapter.countPendingOrInProgress(potId));
	}

	@Test
	void releaseReturnsClaimedTaskToPending() {
		PotId potId = PotId.of(UUID.randomUUID());
		adapter.upsertComputeBalancesTask(event(potId, 2));
		ProjectionTaskClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-1").getFirst();

		assertTrue(adapter.release(claim.task().id(), claim.claimToken()));

		assertEquals(1, adapter.countPendingOrInProgress());
		assertEquals(1, adapter.claimPending(10, Duration.ofSeconds(30), "fetcher-2").size());
	}

	@Test
	void claimsOnlyProjectionTasksFromRequestedPartition() {
		PotId segment0PotId = potIdForSegment(0, 2);
		PotId segment1PotId = potIdForSegment(1, 2);
		adapter.upsertComputeBalancesTask(event(segment0PotId, 2));
		adapter.upsertComputeBalancesTask(event(segment1PotId, 2));

		ProjectionTaskClaim segment0Claim = adapter.claimPending(
				10,
				Duration.ofSeconds(30),
				"fetcher-0",
				new ProjectionPartition(0, 2)).getFirst();
		ProjectionTaskClaim segment1Claim = adapter.claimPending(
				10,
				Duration.ofSeconds(30),
				"fetcher-1",
				new ProjectionPartition(1, 2)).getFirst();

		assertEquals(segment0PotId, segment0Claim.task().potId());
		assertEquals(segment1PotId, segment1Claim.task().potId());
	}

	private static BusinessEventEnvelope event(PotId potId, long version) {
		UUID eventId = UUID.randomUUID();
		return new BusinessEventEnvelope(
				eventId,
				"PotCreatedEvent",
				potId,
				potId.value(),
				version,
				"{}",
				"trace",
				42L,
				Instant.now());
	}

	private static PotId potIdForSegment(int segmentIndex, int segmentCount) {
		for (int index = 0; index < 1_000; index++) {
			PotId potId = PotId.of(UUID.nameUUIDFromBytes(
					("pot-" + segmentIndex + "-" + index).getBytes(StandardCharsets.UTF_8)));
			if (PotPartitioner.segmentOf(potId, segmentCount) == segmentIndex) {
				return potId;
			}
		}
		throw new IllegalStateException("No potId found for segment " + segmentIndex);
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration.toMillis());
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", exception);
		}
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
