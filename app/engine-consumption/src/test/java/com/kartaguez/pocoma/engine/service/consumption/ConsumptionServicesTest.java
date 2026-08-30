package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.kartaguez.pocoma.engine.port.in.consumption.input.CompleteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FailConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ReleaseConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyCompleted;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyFailed;
import com.kartaguez.pocoma.engine.exception.MissingTerminalConsumptionFailureException;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

class ConsumptionServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final WorkerId WORKER = new WorkerId("worker-1");

	@Test
	void acquisitionCreatesTheSlotLazily() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey key = key("work", "1");

		Claim acquired = acquired(service(claims).tryAcquire(request(key, WORKER)));

		assertEquals(1, claims.slotCount());
		assertEquals(key, acquired.consumptionKey());
		assertEquals(1, claims.slot(key).revision());
	}

	@Test
	void onlyOneConcurrentAcquisitionWinsTheCasForAnAbsentSlot() throws Exception {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		TryAcquireConsumptionService service = service(claims);
		ConsumptionKey key = key("work", "1");
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<TryAcquireConsumptionResult> first = executor.submit(() -> {
				start.await();
				return service.tryAcquire(request(key, new WorkerId("worker-1")));
			});
			Future<TryAcquireConsumptionResult> second = executor.submit(() -> {
				start.await();
				return service.tryAcquire(request(key, new WorkerId("worker-2")));
			});
			start.countDown();
			assertEquals(1, (first.get() instanceof Acquired ? 1 : 0)
					+ (second.get() instanceof Acquired ? 1 : 0));
		}
		assertEquals(1, claims.slotCount());
		assertEquals(1, claims.claimCount());
	}

	@Test
	void expiredClaimIsReplacedAndOldTokenIsFenced() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey key = key("work", "1");
		Claim oldClaim = Claim.active(ClaimId.generate(), key, ClaimToken.generate(), WORKER,
				NOW.minusSeconds(31), LEASE);
		claims.tryAcquire(ConsumptionSlot.initial(key), oldClaim, NOW.minusSeconds(31));

		Claim reclaimed = acquired(service(claims).tryAcquire(request(key, new WorkerId("worker-2"))));

		assertNotEquals(oldClaim.token(), reclaimed.token());
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteConsumptionService(claims, CLOCK)
						.complete(new CompleteConsumptionInput(key, oldClaim.token())));
		assertTrue(claims.findClaim(oldClaim.claimId()).orElseThrow().invalidatedAt().isPresent());
	}

	@Test
	void completeFailAndReleaseMutateClaimAndSlotAtomically() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey completedKey = key("work", "completed");
		ConsumptionKey failedKey = key("work", "failed");
		ConsumptionKey releasedKey = key("work", "released");
		Claim completed = acquired(service(claims).tryAcquire(request(completedKey, WORKER)));
		Claim failed = acquired(service(claims).tryAcquire(request(failedKey, WORKER)));
		Claim released = acquired(service(claims).tryAcquire(request(releasedKey, WORKER)));
		ProcessingFailure failure = new ProcessingFailure("business", "rejected", NOW);

		assertEquals(ConsumptionOutcome.APPLIED, new CompleteConsumptionService(claims, CLOCK)
				.complete(new CompleteConsumptionInput(completedKey, completed.token())));
		assertEquals(ConsumptionOutcome.APPLIED, new FailConsumptionService(claims, CLOCK)
				.fail(new FailConsumptionInput(failedKey, failed.token(), failure)));
		assertEquals(ConsumptionOutcome.APPLIED, new ReleaseConsumptionService(claims, CLOCK)
				.release(new ReleaseConsumptionInput(releasedKey, released.token())));

		assertEquals(ConsumptionStatus.DONE, claims.slot(completedKey).status());
		assertEquals(ConsumptionStatus.DONE, claims.slot(failedKey).status());
		assertEquals(ConsumptionStatus.PENDING, claims.slot(releasedKey).status());
		assertEquals(failure, claims.findClaim(failed.claimId()).orElseThrow().failure().orElseThrow());
		assertFalse(claims.findClaim(released.claimId()).orElseThrow().isActiveAt(NOW));
	}

	@Test
	void wrongTokenCannotCompleteFailOrRelease() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey key = key("work", "1");
		acquired(service(claims).tryAcquire(request(key, WORKER)));
		ClaimToken wrongToken = ClaimToken.generate();
		ProcessingFailure failure = new ProcessingFailure("technical", "failure", NOW);

		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteConsumptionService(claims, CLOCK)
						.complete(new CompleteConsumptionInput(key, wrongToken)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new FailConsumptionService(claims, CLOCK)
						.fail(new FailConsumptionInput(key, wrongToken, failure)));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new ReleaseConsumptionService(claims, CLOCK)
						.release(new ReleaseConsumptionInput(key, wrongToken)));
		assertEquals(ConsumptionStatus.PENDING, claims.slot(key).status());
	}

	@Test
	void identicalComponentsInDifferentNamespacesAreIndependent() {
		InMemoryClaimPort claims = new InMemoryClaimPort();

		assertInstanceOf(Acquired.class, service(claims).tryAcquire(request(key("alpha", "42"), WORKER)));
		assertInstanceOf(Acquired.class, service(claims).tryAcquire(request(key("beta", "42"), WORKER)));
		assertEquals(2, claims.slotCount());
	}

	@Test
	void terminalSlotsReturnTheirAuthoritativeOutcomeWithoutProposingANewClaim() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey completedKey = key("work", "completed");
		ConsumptionKey failedKey = key("work", "failed");
		ProcessingFailure failure = new ProcessingFailure("business", "rejected", NOW);
		Claim completed = acquired(service(claims).tryAcquire(request(completedKey, WORKER)));
		Claim failed = acquired(service(claims).tryAcquire(request(failedKey, WORKER)));
		new CompleteConsumptionService(claims, CLOCK)
				.complete(new CompleteConsumptionInput(completedKey, completed.token()));
		new FailConsumptionService(claims, CLOCK)
				.fail(new FailConsumptionInput(failedKey, failed.token(), failure));
		int claimCount = claims.claimCount();

		assertInstanceOf(AlreadyCompleted.class, service(claims).tryAcquire(request(completedKey, WORKER)));
		assertEquals(failure, assertInstanceOf(AlreadyFailed.class,
				service(claims).tryAcquire(request(failedKey, WORKER))).failure());
		assertEquals(claimCount, claims.claimCount());
	}

	@Test
	void failedSlotWithoutTerminalFailureIsReportedAsCorruption() {
		InMemoryClaimPort claims = new InMemoryClaimPort();
		ConsumptionKey key = key("work", "corrupt");
		claims.slots.put(key, ConsumptionSlot.initial(key).failed());

		MissingTerminalConsumptionFailureException exception = assertThrows(
				MissingTerminalConsumptionFailureException.class,
				() -> service(claims).tryAcquire(request(key, WORKER)));

		assertEquals(key, exception.consumptionKey());
	}

	private static TryAcquireConsumptionService service(InMemoryClaimPort claims) {
		return new TryAcquireConsumptionService(claims, CLOCK);
	}

	private static Claim acquired(TryAcquireConsumptionResult result) {
		return assertInstanceOf(Acquired.class, result).claim();
	}

	private static TryAcquireConsumptionInput request(ConsumptionKey key, WorkerId workerId) {
		return new TryAcquireConsumptionInput(key, workerId, LEASE);
	}

	private static ConsumptionKey key(String namespace, String component) {
		return new ConsumptionKey(namespace, List.of(component));
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
		public synchronized Optional<ProcessingFailure> findTerminalFailure(ConsumptionKey key) {
			return claims.values().stream()
					.filter(claim -> claim.consumptionKey().equals(key))
					.filter(claim -> claim.invalidatedAt().isEmpty())
					.flatMap(claim -> claim.failure().stream())
					.findFirst();
		}

		@Override
		public synchronized Optional<Claim> tryAcquire(
				ConsumptionSlot observedSlot, Claim proposedClaim, Instant now) {
			ConsumptionSlot actual = slots.computeIfAbsent(observedSlot.consumptionKey(), ConsumptionSlot::initial);
			if (actual.revision() != observedSlot.revision() || actual.status() != ConsumptionStatus.PENDING) {
				return Optional.empty();
			}
			Optional<Claim> current = currentClaim(actual.consumptionKey());
			if (current.map(claim -> claim.isActiveAt(now)).orElse(false)) {
				return Optional.empty();
			}
			current.ifPresent(claim -> claims.put(claim.claimId(), claim.invalidateAt(now)));
			claims.put(proposedClaim.claimId(), proposedClaim);
			slots.put(actual.consumptionKey(), actual.acquired());
			return Optional.of(proposedClaim);
		}

		@Override
		public synchronized ConsumptionOutcome endCurrentClaim(
				ConsumptionKey key, ClaimToken token, Instant now) {
			return mutate(key, token, now, Mutation.COMPLETE, null);
		}

		@Override
		public synchronized ConsumptionOutcome failCurrentClaim(
				ConsumptionKey key, ClaimToken token, ProcessingFailure failure, Instant now) {
			return mutate(key, token, now, Mutation.FAIL, failure);
		}

		@Override
		public synchronized ConsumptionOutcome releaseCurrentClaim(
				ConsumptionKey key, ClaimToken token, Instant now) {
			return mutate(key, token, now, Mutation.RELEASE, null);
		}

		private ConsumptionOutcome mutate(
				ConsumptionKey key, ClaimToken token, Instant now, Mutation mutation, ProcessingFailure failure) {
			ConsumptionSlot slot = slots.get(key);
			Claim claim = currentClaim(key).orElse(null);
			if (slot == null || slot.status() != ConsumptionStatus.PENDING
					|| claim == null || !claim.isOwnedBy(token, now)) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			claims.put(claim.claimId(), switch (mutation) {
				case COMPLETE -> claim.endAt(now);
				case FAIL -> claim.failAt(now, failure);
				case RELEASE -> claim.invalidateAt(now);
			});
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

		private ConsumptionSlot slot(ConsumptionKey key) {
			return slots.get(key);
		}

		private int slotCount() {
			return slots.size();
		}

		private int claimCount() {
			return claims.size();
		}

		private enum Mutation { COMPLETE, FAIL, RELEASE }
	}
}
