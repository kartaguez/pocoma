package com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.model.ProjectionTask;

class DirectSegmentedProjectionWorkerTest {

	@Test
	void preservesSubmissionOrderForSamePot() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		RecordingComputePotBalancesUseCase useCase = new RecordingComputePotBalancesUseCase(2);
		DirectSegmentedProjectionWorker worker = worker(useCase, 2);
		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "FirstEvent"));
			worker.submit(new ProjectionTask(potId, 3, "SecondEvent"));

			assertTrue(useCase.await());
			assertEquals(List.of(2L, 3L), useCase.versions);
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void retriesTransientFailures() throws InterruptedException {
		PotId potId = PotId.of(UUID.randomUUID());
		RetryingComputePotBalancesUseCase useCase = new RetryingComputePotBalancesUseCase();
		DirectSegmentedProjectionWorker worker = worker(useCase, 1);
		worker.start();
		try {
			worker.submit(new ProjectionTask(potId, 2, "RetryEvent"));

			assertTrue(useCase.await());
			assertEquals(2, useCase.attempts.get());
		}
		finally {
			worker.stop();
		}
	}

	@Test
	void canProcessDifferentPotsOnDifferentSegments() throws InterruptedException {
		PotId firstPotId = PotId.of(UUID.randomUUID());
		DirectSegmentedProjectionWorker probe = worker(new RecordingComputePotBalancesUseCase(0), 2);
		PotId secondPotId = differentSegmentPotId(probe, firstPotId);
		RecordingComputePotBalancesUseCase useCase = new RecordingComputePotBalancesUseCase(2);
		DirectSegmentedProjectionWorker worker = worker(useCase, 2);
		worker.start();
		try {
			worker.submit(new ProjectionTask(firstPotId, 2, "FirstPotEvent"));
			worker.submit(new ProjectionTask(secondPotId, 2, "SecondPotEvent"));

			assertTrue(useCase.await());
			assertEquals(2, useCase.potIds.size());
		}
		finally {
			worker.stop();
		}
	}

	private static DirectSegmentedProjectionWorker worker(ComputePotBalancesUseCase useCase, int threadCount) {
		return new DirectSegmentedProjectionWorker(
				useCase,
				new ProjectionTaskExecutorSettings(
						threadCount,
						1,
						2,
						Duration.ZERO,
						Duration.ZERO));
	}

	private static PotId differentSegmentPotId(DirectSegmentedProjectionWorker worker, PotId existingPotId) {
		int existingSegment = worker.segmentIndex(existingPotId);
		while (true) {
			PotId candidate = PotId.of(UUID.randomUUID());
			if (worker.segmentIndex(candidate) != existingSegment) {
				return candidate;
			}
		}
	}

	private static class RecordingComputePotBalancesUseCase implements ComputePotBalancesUseCase {
		private final CountDownLatch latch;
		private final List<PotId> potIds = Collections.synchronizedList(new ArrayList<>());
		private final List<Long> versions = Collections.synchronizedList(new ArrayList<>());

		private RecordingComputePotBalancesUseCase(int expectedCalls) {
			this.latch = new CountDownLatch(expectedCalls);
		}

		@Override
		public PotBalances computePotBalances(PotId potId, long targetVersion) {
			throw new UnsupportedOperationException("Direct worker must use full projection");
		}

		@Override
		public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
			potIds.add(potId);
			versions.add(targetVersion);
			latch.countDown();
			return new PotBalances(potId, targetVersion, java.util.Map.of());
		}

		boolean await() throws InterruptedException {
			return latch.await(2, TimeUnit.SECONDS);
		}
	}

	private static final class RetryingComputePotBalancesUseCase extends RecordingComputePotBalancesUseCase {
		private final AtomicInteger attempts = new AtomicInteger();

		private RetryingComputePotBalancesUseCase() {
			super(1);
		}

		@Override
		public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("transient");
			}
			return super.computePotBalancesFull(potId, targetVersion);
		}
	}
}
