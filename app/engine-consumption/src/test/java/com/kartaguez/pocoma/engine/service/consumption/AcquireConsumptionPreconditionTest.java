package com.kartaguez.pocoma.engine.service.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;

class AcquireConsumptionPreconditionTest {
	private static final Instant NOW = Instant.parse("2026-09-14T08:00:00Z");
	private static final ConsumptionKey KEY = new ConsumptionKey(
			new ConsumableIdentity("TEST", List.of("1")), new ConsumerIdentity("TEST", List.of()));

	@Test void refusedPreconditionDoesNotTouchPersistence() {
		var calls = new AtomicInteger();
		var service = new AcquireConsumptionService(new StubPersistence(calls), Clock.fixed(NOW, ZoneOffset.UTC));
		var result = service.acquire(new AcquireConsumptionInput(KEY, new WorkerId("worker"),
				new ClaimLease(Duration.ofSeconds(30)), () -> false));
		assertInstanceOf(AcquireResult.NotEligible.class, result);
		assertEquals(0, calls.get());
	}

	@Test void acceptedPreconditionDelegatesExactlyOnce() {
		var calls = new AtomicInteger();
		var service = new AcquireConsumptionService(new StubPersistence(calls), Clock.fixed(NOW, ZoneOffset.UTC));
		service.acquire(new AcquireConsumptionInput(KEY, new WorkerId("worker"),
				new ClaimLease(Duration.ofSeconds(30)), () -> true));
		assertEquals(1, calls.get());
	}

	private record StubPersistence(AtomicInteger calls) implements ConsumptionLifecyclePersistencePort {
		@Override public void terminalize(UUID slotId, ClaimId claimId, ConsumptionFinalization terminal, Instant now) {
			throw new UnsupportedOperationException();
		}
		@Override public void scheduleRetry(UUID slotId, ClaimId claimId, ProcessingFailure failure, Instant nextClaimAt, Instant now) {
			throw new UnsupportedOperationException();
		}

		@Override public AcquireResult acquire(ConsumptionKey key, ClaimId claimId, WorkerId workerId,
				ClaimLease lease, Instant now) {
			calls.incrementAndGet();
			return new AcquireResult.NotReady(now);
		}

	}
}
