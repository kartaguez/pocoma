package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;

@DataJpaTest
@Import(JpaBusinessEventOutboxAdapter.class)
class JpaBusinessEventOutboxAdapterTest {

	@Autowired
	private JpaBusinessEventOutboxAdapter adapter;

	@Autowired
	private JpaBusinessEventOutboxRepository repository;

	@Test
	void appendsAndClaimsBusinessEvents() {
		PotId potId = PotId.of(UUID.randomUUID());

		adapter.append(new PotCreatedEvent(potId, 1));
		List<BusinessEventClaim> claims = adapter.claimPending(10, Duration.ofSeconds(30), "builder-1");

		assertEquals(1, claims.size());
		assertEquals("PotCreatedEvent", claims.getFirst().event().eventType());
		assertEquals(potId, claims.getFirst().event().potId());
		assertEquals(1, adapter.countPendingOrClaimed());
	}

	@Test
	void requiresClaimTokenToMarkDone() {
		adapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		BusinessEventClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "builder-1").getFirst();

		assertFalse(adapter.markDone(claim.event().id(), UUID.randomUUID()));
		assertTrue(adapter.markAccepted(claim.event().id(), claim.claimToken()));
		assertTrue(adapter.markRunning(claim.event().id(), claim.claimToken()));
		assertTrue(adapter.markDone(claim.event().id(), claim.claimToken()));

		assertEquals(0, adapter.countPendingOrClaimed());
	}

	@Test
	void releasedAcceptedEventsCanBeClaimedAgain() {
		adapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		BusinessEventClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "builder-1").getFirst();

		assertTrue(adapter.markAccepted(claim.event().id(), claim.claimToken()));
		assertTrue(adapter.release(claim.event().id(), claim.claimToken()));

		List<BusinessEventClaim> claims = adapter.claimPending(10, Duration.ofSeconds(30), "builder-2");
		assertEquals(1, claims.size());
	}

	@Test
	void failedEventsLeavePendingGauge() {
		adapter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()), 1));
		BusinessEventClaim claim = adapter.claimPending(10, Duration.ofSeconds(30), "builder-1").getFirst();

		assertTrue(adapter.markFailed(claim.event().id(), claim.claimToken(), "boom"));

		assertEquals(0, adapter.countPendingOrClaimed());
		assertTrue(repository.findById(claim.event().id()).isPresent());
	}

	@Test
	void claimsOnlyBusinessEventsFromRequestedPartition() {
		PotId segment0PotId = potIdForSegment(0, 2);
		PotId segment1PotId = potIdForSegment(1, 2);
		adapter.append(new PotCreatedEvent(segment0PotId, 1));
		adapter.append(new PotCreatedEvent(segment1PotId, 1));

		List<BusinessEventClaim> segment0Claims = adapter.claimPending(
				10,
				Duration.ofSeconds(30),
				"builder-0",
				new ProjectionPartition(0, 2));
		List<BusinessEventClaim> segment1Claims = adapter.claimPending(
				10,
				Duration.ofSeconds(30),
				"builder-1",
				new ProjectionPartition(1, 2));

		assertEquals(List.of(segment0PotId), segment0Claims.stream().map(claim -> claim.event().potId()).toList());
		assertEquals(List.of(segment1PotId), segment1Claims.stream().map(claim -> claim.event().potId()).toList());
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

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
