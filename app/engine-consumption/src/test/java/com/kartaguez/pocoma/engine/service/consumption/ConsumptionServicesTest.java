package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.processing.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.context.consumption.ConsumableCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;
import com.kartaguez.pocoma.engine.port.out.consumption.CommandPort;

class ConsumptionServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerId WORKER = new WorkerId("worker-1");

	@Test
	void commandCreationDoesNotCreateSlotAndClaimingCreatesItLazily() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		InMemoryCommandPort commands = new InMemoryCommandPort(commandWithoutPot(1, NOW));
		ClaimNextCommandService service = new ClaimNextCommandService(commands, claims, CLOCK);

		assertEquals(0, claims.slotCount());

		CommandClaimResult result = service.claimNext(request(WorkerSegment.single())).orElseThrow();

		assertEquals(1, claims.slotCount());
		assertEquals(result.command().consumptionKey(), result.claim().consumptionKey());
	}

	@Test
	void onlyOneConcurrentCasCanAcquireAnInitiallyAbsentSlot() throws Exception {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey key = commandKey(uuid(1));
		ConsumptionSlot absentSnapshot = ConsumptionSlot.initial(key);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Optional<Claim>> first = executor.submit(() -> {
				start.await();
				return claims.tryAcquire(absentSnapshot, claim(key, "worker-1", NOW), NOW);
			});
			Future<Optional<Claim>> second = executor.submit(() -> {
				start.await();
				return claims.tryAcquire(absentSnapshot, claim(key, "worker-2", NOW), NOW);
			});
			start.countDown();

			assertEquals(1, (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0));
		}
		assertEquals(1, claims.slotCount());
		assertEquals(1, claims.claimCount());
	}

	@Test
	void loserContinuesWithTheNextCommandInCreationOrder() {
		ConsumableCommand first = commandWithoutPot(1, NOW);
		ConsumableCommand second = commandWithoutPot(2, NOW.plusSeconds(1));
		InMemoryClaimPort claims = new InMemoryClaimPort();
		claims.tryAcquire(ConsumptionSlot.initial(first.consumptionKey()),
				claim(first.consumptionKey(), "other-worker", NOW), NOW);
		ClaimNextCommandService service = new ClaimNextCommandService(
				new InMemoryCommandPort(second, first), claims, CLOCK);

		CommandClaimResult result = service.claimNext(request(WorkerSegment.single())).orElseThrow();

		assertEquals(second.commandId(), result.command().commandId());
	}

	@Test
	void expiredClaimIsReplacedAndItsOldTokenCannotComplete() {
		ConsumableCommand command = commandWithoutPot(1, NOW);
		InMemoryClaimPort claims = new InMemoryClaimPort();
		Claim oldClaim = claim(command.consumptionKey(), "old-worker", NOW.minusSeconds(31));
		claims.tryAcquire(ConsumptionSlot.initial(command.consumptionKey()), oldClaim, NOW.minusSeconds(31));
		InMemoryCommandPort commands = new InMemoryCommandPort(command);
		CommandClaimResult reclaimed = new ClaimNextCommandService(commands, claims, CLOCK)
				.claimNext(request(WorkerSegment.single())).orElseThrow();

		assertNotEquals(oldClaim.token(), reclaimed.claim().token());
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteCommandService(commands, claims, CLOCK)
						.complete(new CompleteCommandInput(command.commandId(), oldClaim.token())));
		assertEquals(ConsumptionStatus.READY, commands.command(command.commandId()).status());
	}

	@Test
	void releaseInvalidatesClaimButLeavesCommandReady() {
		ConsumableCommand command = commandWithoutPot(1, NOW);
		InMemoryClaimPort claims = new InMemoryClaimPort();
		InMemoryCommandPort commands = new InMemoryCommandPort(command);
		Claim claim = new ClaimNextCommandService(commands, claims, CLOCK)
				.claimNext(request(WorkerSegment.single())).orElseThrow().claim();

		ConsumptionOutcome outcome = new ReleaseCommandService(claims, CLOCK)
				.release(new ReleaseCommandInput(command.commandId(), claim.token()));

		assertEquals(ConsumptionOutcome.APPLIED, outcome);
		assertEquals(ConsumptionStatus.READY, commands.command(command.commandId()).status());
		assertFalse(claims.findClaim(claim.claimId()).orElseThrow().isActiveAt(NOW));
	}

	@Test
	void noPotCommandsAreVisibleToEverySegmentAndPotCommandsOnlyToTheirOwner() {
		ConsumableCommand global = commandWithoutPot(1, NOW);
		UUID potId = uuid(20);
		ConsumableCommand partitioned = commandForPot(2, potId, NOW.plusSeconds(1));
		InMemoryCommandPort commands = new InMemoryCommandPort(global, partitioned);
		int owner = Math.floorMod(PartitionHash.forPot(potId).value(), 4);

		for (int index = 0; index < 4; index++) {
			WorkerSegment segment = new WorkerSegment(index, 4);
			assertTrue(global.isEligibleFor(segment));
			assertEquals(index == owner, partitioned.isEligibleFor(segment));
			assertEquals(global.commandId(), commands.findNextReady(segment, Optional.empty()).orElseThrow().commandId());
		}
	}

	@Test
	void sameEventHasIndependentSlotsForEachPipeline() {
		UUID eventId = uuid(42);
		ConsumptionKey firstPipeline = new ConsumptionKey(
				"event", List.of("balances", "1", eventId.toString()));
		ConsumptionKey secondPipeline = new ConsumptionKey(
				"event", List.of("notifications", "1", eventId.toString()));
		InMemoryClaimPort claims = new InMemoryClaimPort();

		assertTrue(claims.tryAcquire(ConsumptionSlot.initial(firstPipeline), claim(firstPipeline, "w1", NOW), NOW).isPresent());
		assertTrue(claims.tryAcquire(ConsumptionSlot.initial(secondPipeline), claim(secondPipeline, "w2", NOW), NOW).isPresent());
		assertEquals(2, claims.slotCount());
	}

	private static ClaimNextCommandInput request(WorkerSegment segment) {
		return new ClaimNextCommandInput(WORKER, LEASE, segment);
	}

	private static Claim claim(ConsumptionKey key, String worker, Instant claimedAt) {
		return Claim.active(ClaimId.generate(), key, ClaimToken.generate(), new WorkerId(worker), claimedAt, LEASE);
	}

	private static ConsumableCommand commandWithoutPot(int id, Instant createdAt) {
		return new ConsumableCommand(uuid(id), Optional.empty(), createdAt,
				new CreatePotCommand("Trip", uuid(100 + id)), ConsumptionStatus.READY);
	}

	private static ConsumableCommand commandForPot(int id, UUID potId, Instant createdAt) {
		return new ConsumableCommand(uuid(id), Optional.of(potId), createdAt,
				new CreatePotCommand("Trip", uuid(100 + id)), ConsumptionStatus.READY);
	}

	private static UUID uuid(int suffix) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", suffix));
	}

	private static ConsumptionKey commandKey(UUID commandId) {
		return new ConsumptionKey("command", List.of(commandId.toString()));
	}

	private static final class InMemoryCommandPort implements CommandPort {
		private final Map<UUID, ConsumableCommand> commands = new HashMap<>();

		private InMemoryCommandPort(ConsumableCommand... commands) {
			for (ConsumableCommand command : commands) {
				this.commands.put(command.commandId(), command);
			}
		}

		@Override
		public Optional<ConsumableCommand> findNextReady(
				WorkerSegment segment, Optional<CommandOrderingKey> afterExclusive) {
			return commands.values().stream()
					.filter(command -> command.isEligibleFor(segment))
					.filter(command -> afterExclusive.map(cursor -> command.orderingKey().compareTo(cursor) > 0).orElse(true))
					.min(Comparator.comparing(ConsumableCommand::orderingKey));
		}

		@Override
		public void markCompleted(UUID commandId) {
			updateStatus(commandId, ConsumptionStatus.COMPLETED);
		}

		@Override
		public void markFailed(UUID commandId, ProcessingFailure failure) {
			updateStatus(commandId, ConsumptionStatus.FAILED);
		}

		private void updateStatus(UUID commandId, ConsumptionStatus status) {
			ConsumableCommand command = command(commandId);
			commands.put(commandId, new ConsumableCommand(command.commandId(), command.potId(), command.createdAt(),
					command.intent(), status));
		}

		private ConsumableCommand command(UUID commandId) {
			return commands.get(commandId);
		}
	}

	private static final class InMemoryClaimPort implements ClaimPort {
		private final Map<ConsumptionKey, ConsumptionSlot> slots = new HashMap<>();
		private final Map<ClaimId, Claim> claims = new HashMap<>();

		@Override
		public synchronized Optional<ConsumptionSlot> findSlot(ConsumptionKey key) {
			return Optional.ofNullable(slots.get(key));
		}

		@Override
		public synchronized Optional<Claim> findClaim(ClaimId claimId) {
			return Optional.ofNullable(claims.get(claimId));
		}

		@Override
		public synchronized Optional<Claim> tryAcquire(
				ConsumptionSlot observedSlot, Claim proposedClaim, Instant now) {
			ConsumptionSlot actual = slots.computeIfAbsent(observedSlot.consumptionKey(), ConsumptionSlot::initial);
			if (actual.revision() != observedSlot.revision()
					|| actual.status() != ConsumptionStatus.READY) {
				return Optional.empty();
			}
			Optional<Claim> current = currentClaim(actual.consumptionKey());
			if (current.map(claim -> claim.isActiveAt(now)).orElse(false)) {
				return Optional.empty();
			}
			current.filter(claim -> claim.invalidatedAt().isEmpty() && claim.endedAt().isEmpty())
					.ifPresent(claim -> claims.put(claim.claimId(), claim.invalidateAt(now)));
			claims.put(proposedClaim.claimId(), proposedClaim);
			slots.put(actual.consumptionKey(), actual.acquired());
			return Optional.of(proposedClaim);
		}

		@Override
		public synchronized ConsumptionOutcome endCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now) {
			return mutateCurrent(key, token, now, ClaimMutation.COMPLETE, null);
		}

		@Override
		public synchronized ConsumptionOutcome failCurrentClaim(
				ConsumptionKey key, ClaimToken token, ProcessingFailure failure, Instant now) {
			return mutateCurrent(key, token, now, ClaimMutation.FAIL, failure);
		}

		@Override
		public synchronized ConsumptionOutcome releaseCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now) {
			return mutateCurrent(key, token, now, ClaimMutation.RELEASE, null);
		}

		private ConsumptionOutcome mutateCurrent(
				ConsumptionKey key, ClaimToken token, Instant now,
				ClaimMutation mutation, ProcessingFailure failure) {
			ConsumptionSlot slot = slots.get(key);
			if (slot == null || slot.status() != ConsumptionStatus.READY) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			Claim claim = currentClaim(key).orElse(null);
			if (claim == null || !claim.isOwnedBy(token, now)) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			Claim updated = switch (mutation) {
				case COMPLETE -> claim.endAt(now);
				case FAIL -> claim.failAt(now, failure);
				case RELEASE -> claim.invalidateAt(now);
			};
			claims.put(claim.claimId(), updated);
			slots.put(key, switch (mutation) {
				case COMPLETE -> slot.completed();
				case FAIL -> slot.failed();
				case RELEASE -> slot.released();
			});
			return ConsumptionOutcome.APPLIED;
		}

		private Optional<Claim> currentClaim(ConsumptionKey key) {
			return claims.values().stream()
					.filter(claim -> claim.consumptionKey().equals(key))
					.filter(claim -> claim.invalidatedAt().isEmpty() && claim.endedAt().isEmpty())
					.max(Comparator.comparing(Claim::claimedAt));
		}

		private enum ClaimMutation { COMPLETE, FAIL, RELEASE }

		private synchronized int slotCount() {
			return slots.size();
		}

		private synchronized int claimCount() {
			return claims.size();
		}
	}
}
