package com.kartaguez.pocoma.engine.service.processing.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.processing.event.input.ClaimNextEventInput;
import com.kartaguez.pocoma.engine.port.in.processing.event.result.EventClaimResult;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;

class ConcurrentEventProcessingTest {

	private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final RecordedEvent<PotCreatedEvent> EVENT = new RecordedEvent<>(uuid(1),
			new PotCreatedEvent(PotId.of(uuid(2)), 1), NOW, EventTraceMetadata.empty());

	@Test
	void samePipelineAndVersionHasOneWinner() throws Exception {
		PipelineDefinition pipeline = pipeline("balances", 1);
		List<Optional<EventClaimResult>> results = race(pipeline, pipeline);
		assertEquals(1, results.stream().filter(Optional::isPresent).count());
	}

	@Test
	void distinctPipelinesConsumeTheSameEventIndependently() throws Exception {
		List<Optional<EventClaimResult>> results = race(pipeline("balances", 1), pipeline("settlements", 1));
		assertEquals(2, results.stream().filter(Optional::isPresent).count());
	}

	@Test
	void distinctVersionsConsumeTheSameEventIndependently() throws Exception {
		List<Optional<EventClaimResult>> results = race(pipeline("balances", 1), pipeline("balances", 2));
		assertEquals(2, results.stream().filter(Optional::isPresent).count());
	}

	private static List<Optional<EventClaimResult>> race(
			PipelineDefinition firstPipeline, PipelineDefinition secondPipeline) throws Exception {
		AtomicAcquisition acquisition = new AtomicAcquisition();
		ClaimNextEventService service = new ClaimNextEventService(new SingleEventPort(), acquisition);
		return race(
				() -> service.claimNext(input("worker-a", firstPipeline)),
				() -> service.claimNext(input("worker-b", secondPipeline)));
	}

	private static ClaimNextEventInput input(String worker, PipelineDefinition pipeline) {
		return new ClaimNextEventInput(new WorkerId(worker), LEASE, WorkerSegment.single(), pipeline);
	}

	private static PipelineDefinition pipeline(String id, int version) {
		return new PipelineDefinition(PipelineId.of(id), version);
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}

	private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<T> a = executor.submit(() -> { start.await(); return first.call(); });
			Future<T> b = executor.submit(() -> { start.await(); return second.call(); });
			start.countDown();
			return List.of(a.get(2, TimeUnit.SECONDS), b.get(2, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private static final class SingleEventPort implements EventPort {
		@Override
		public Optional<RecordedEvent<? extends BusinessEvent>> findNextCandidate(
				PipelineDefinition pipeline, WorkerSegment segment, Optional<EventOrderingKey> afterExclusive) {
			return afterExclusive.isEmpty() ? Optional.of(EVENT) : Optional.empty();
		}
	}

	private static final class AtomicAcquisition implements TryAcquireConsumptionUseCase {
		private final java.util.Set<ConsumptionKey> acquired = new java.util.HashSet<>();

		@Override
		public synchronized Optional<Claim> tryAcquire(TryAcquireConsumptionInput input) {
			if (!acquired.add(input.consumptionKey())) return Optional.empty();
			return Optional.of(Claim.active(ClaimId.generate(), input.consumptionKey(), ClaimToken.generate(),
					input.workerId(), NOW, input.lease()));
		}
	}
}
