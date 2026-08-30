package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;

class ConcurrentConsumptionServicesTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final ConsumptionKey KEY = new ConsumptionKey("work", List.of("42"));

	@Test
	void simultaneousAcquisitionsCreateOneSlotAndOneActiveClaim() throws Exception {
		AtomicClaimPort port = new AtomicClaimPort();
		TryAcquireConsumptionService service = new TryAcquireConsumptionService(port, CLOCK);
		CountDownLatch start = new CountDownLatch(1);

		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<TryAcquireConsumptionResult> first = executor.submit(() -> acquireAfter(start, service, "worker-1", KEY));
			Future<TryAcquireConsumptionResult> second = executor.submit(() -> acquireAfter(start, service, "worker-2", KEY));
			start.countDown();
			int winners = (first.get(2, TimeUnit.SECONDS) instanceof Acquired ? 1 : 0)
					+ (second.get(2, TimeUnit.SECONDS) instanceof Acquired ? 1 : 0);
			assertEquals(1, winners);
		}

		assertEquals(1, port.slots.size());
		assertEquals(1, port.activeClaims(KEY, NOW));
		assertEquals(2, port.proposedTokens.size());
		assertNotEquals(port.proposedTokens.get(0), port.proposedTokens.get(1));
	}

	@Test
	void differentKeysCanBeAcquiredConcurrently() throws Exception {
		AtomicClaimPort port = new AtomicClaimPort();
		TryAcquireConsumptionService service = new TryAcquireConsumptionService(port, CLOCK);
		CountDownLatch start = new CountDownLatch(1);
		ConsumptionKey other = new ConsumptionKey("work", List.of("43"));

		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<TryAcquireConsumptionResult> first = executor.submit(() -> acquireAfter(start, service, "worker-1", KEY));
			Future<TryAcquireConsumptionResult> second = executor.submit(() -> acquireAfter(start, service, "worker-2", other));
			start.countDown();
			assertInstanceOf(Acquired.class, first.get(2, TimeUnit.SECONDS));
			assertInstanceOf(Acquired.class, second.get(2, TimeUnit.SECONDS));
		}
		assertEquals(2, port.slots.size());
	}

	@Test
	void commandEventAndTaskNamespacesRemainIsolatedForIdenticalIdentifiers() {
		String id = "00000000-0000-0000-0000-000000000042";
		ConsumptionKey command = new ConsumptionKey("command", List.of(id));
		ConsumptionKey task = new ConsumptionKey("task", List.of(id));
		ConsumptionKey eventV1 = new ConsumptionKey("event", List.of("balances", "1", id));
		ConsumptionKey eventV2 = new ConsumptionKey("event", List.of("balances", "2", id));
		ConsumptionKey eventOtherPipeline = new ConsumptionKey("event", List.of("settlements", "1", id));
		AtomicClaimPort port = new AtomicClaimPort();
		TryAcquireConsumptionService service = new TryAcquireConsumptionService(port, CLOCK);

		for (ConsumptionKey key : List.of(command, task, eventV1, eventV2, eventOtherPipeline)) {
			assertInstanceOf(Acquired.class, service.tryAcquire(request(key, "worker")));
		}

		assertEquals(5, port.slots.size());
	}

	@Test
	void expiryReleaseAndTerminalStatesFenceTokensAndControlReacquisition() {
		AtomicClaimPort port = new AtomicClaimPort();
		Claim expired = Claim.active(ClaimId.generate(), KEY, ClaimToken.generate(), new WorkerId("old"),
				NOW.minusSeconds(30), LEASE);
		port.tryAcquire(ConsumptionSlot.initial(KEY), expired, NOW.minusSeconds(30));

		Claim reclaimed = acquired(new TryAcquireConsumptionService(port, CLOCK)
				.tryAcquire(request(KEY, "new")));
		assertNotEquals(expired.token(), reclaimed.token());
		assertFalse(port.findClaim(expired.claimId()).orElseThrow().isActiveAt(NOW));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new CompleteConsumptionService(port, CLOCK).complete(new CompleteConsumptionInput(KEY, expired.token())));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new FailConsumptionService(port, CLOCK).fail(new FailConsumptionInput(KEY, expired.token(), failure())));
		assertEquals(ConsumptionOutcome.CLAIM_OWNERSHIP_LOST,
				new ReleaseConsumptionService(port, CLOCK).release(new ReleaseConsumptionInput(KEY, expired.token())));

		assertEquals(ConsumptionOutcome.APPLIED,
				new ReleaseConsumptionService(port, CLOCK).release(new ReleaseConsumptionInput(KEY, reclaimed.token())));
		Claim third = acquired(new TryAcquireConsumptionService(port, CLOCK).tryAcquire(request(KEY, "third")));
		assertNotEquals(reclaimed.token(), third.token());
		assertEquals(ConsumptionOutcome.APPLIED,
				new CompleteConsumptionService(port, CLOCK).complete(new CompleteConsumptionInput(KEY, third.token())));
		assertInstanceOf(AlreadyCompleted.class,
				new TryAcquireConsumptionService(port, CLOCK).tryAcquire(request(KEY, "fourth")));

		ConsumptionKey failedKey = new ConsumptionKey("work", List.of("failed"));
		Claim failed = acquired(new TryAcquireConsumptionService(port, CLOCK).tryAcquire(request(failedKey, "worker")));
		assertEquals(ConsumptionOutcome.APPLIED,
				new FailConsumptionService(port, CLOCK).fail(new FailConsumptionInput(failedKey, failed.token(), failure())));
		AlreadyFailed alreadyFailed = assertInstanceOf(AlreadyFailed.class,
				new TryAcquireConsumptionService(port, CLOCK).tryAcquire(request(failedKey, "other")));
		assertEquals(failure(), alreadyFailed.failure());
	}

	private static TryAcquireConsumptionResult acquireAfter(
			CountDownLatch start, TryAcquireConsumptionService service, String worker, ConsumptionKey key)
			throws InterruptedException {
		start.await();
		return service.tryAcquire(request(key, worker));
	}

	private static Claim acquired(TryAcquireConsumptionResult result) {
		return assertInstanceOf(Acquired.class, result).claim();
	}

	private static TryAcquireConsumptionInput request(ConsumptionKey key, String worker) {
		return new TryAcquireConsumptionInput(key, new WorkerId(worker), LEASE);
	}

	private static ProcessingFailure failure() {
		return new ProcessingFailure("test", "failed", NOW);
	}

	private static final class AtomicClaimPort implements ClaimPort {
		private final Map<ConsumptionKey, ConsumptionSlot> slots = new HashMap<>();
		private final Map<ClaimId, Claim> claims = new HashMap<>();
		private final List<ClaimToken> proposedTokens = new ArrayList<>();

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
				ConsumptionSlot observed, Claim proposed, Instant now) {
			proposedTokens.add(proposed.token());
			ConsumptionSlot actual = slots.computeIfAbsent(observed.consumptionKey(), ConsumptionSlot::initial);
			if (actual.revision() != observed.revision() || actual.status() != ConsumptionStatus.PENDING) {
				return Optional.empty();
			}
			Optional<Claim> current = current(actual.consumptionKey());
			if (current.filter(claim -> claim.isActiveAt(now)).isPresent()) {
				return Optional.empty();
			}
			current.ifPresent(claim -> claims.put(claim.claimId(), claim.invalidateAt(now)));
			claims.put(proposed.claimId(), proposed);
			slots.put(actual.consumptionKey(), actual.acquired());
			return Optional.of(proposed);
		}

		@Override
		public synchronized ConsumptionOutcome endCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now) {
			return transition(key, token, now, false, null);
		}

		@Override
		public synchronized ConsumptionOutcome failCurrentClaim(
				ConsumptionKey key, ClaimToken token, ProcessingFailure failure, Instant now) {
			return transition(key, token, now, true, failure);
		}

		@Override
		public synchronized ConsumptionOutcome releaseCurrentClaim(ConsumptionKey key, ClaimToken token, Instant now) {
			Optional<Claim> current = current(key);
			if (current.filter(claim -> claim.isOwnedBy(token, now)).isEmpty()) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			Claim claim = current.orElseThrow();
			claims.put(claim.claimId(), claim.invalidateAt(now));
			slots.put(key, slots.get(key).released());
			return ConsumptionOutcome.APPLIED;
		}

		private ConsumptionOutcome transition(
				ConsumptionKey key, ClaimToken token, Instant now, boolean failed, ProcessingFailure failure) {
			Optional<Claim> current = current(key);
			if (current.filter(claim -> claim.isOwnedBy(token, now)).isEmpty()) {
				return ConsumptionOutcome.CLAIM_OWNERSHIP_LOST;
			}
			Claim claim = current.orElseThrow();
			claims.put(claim.claimId(), failed ? claim.failAt(now, failure) : claim.endAt(now));
			slots.put(key, failed ? slots.get(key).failed() : slots.get(key).completed());
			return ConsumptionOutcome.APPLIED;
		}

		private Optional<Claim> current(ConsumptionKey key) {
			return claims.values().stream()
					.filter(claim -> claim.consumptionKey().equals(key))
					.filter(claim -> claim.endedAt().isEmpty() && claim.invalidatedAt().isEmpty())
					.findFirst();
		}

		private long activeClaims(ConsumptionKey key, Instant now) {
			return claims.values().stream()
					.filter(claim -> claim.consumptionKey().equals(key) && claim.isActiveAt(now))
					.count();
		}
	}
}
