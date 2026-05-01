package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;

class WakeablePollingLoopTest {

	@Test
	void wakesImmediatelyWhenExpectedSignalIsPublished() throws InterruptedException {
		InMemoryProjectionWorkerWakeBus wakeBus = new InMemoryProjectionWorkerWakeBus();
		CountDownLatch awakened = new CountDownLatch(1);

		try (WakeablePollingLoop loop = new WakeablePollingLoop(
				wakeBus,
				Set.of(ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE),
				Duration.ofSeconds(5),
				true)) {
			Thread thread = new Thread(() -> {
				loop.awaitWakeUp();
				awakened.countDown();
			});
			thread.start();

			wakeBus.publish(
					ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE,
					PotId.of(UUID.randomUUID()));

			assertTrue(awakened.await(500, TimeUnit.MILLISECONDS));
			thread.join(500);
		}
	}

	@Test
	void wakesOnlyWhenPotBelongsToSubscribedPartition() throws InterruptedException {
		InMemoryProjectionWorkerWakeBus wakeBus = new InMemoryProjectionWorkerWakeBus();
		CountDownLatch awakened = new CountDownLatch(1);
		PotId segment0PotId = potIdForSegment(0, 2);
		PotId segment1PotId = potIdForSegment(1, 2);

		try (WakeablePollingLoop loop = new WakeablePollingLoop(
				wakeBus,
				Set.of(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE),
				new ProjectionPartition(0, 2),
				Duration.ofSeconds(5),
				true)) {
			Thread thread = new Thread(() -> {
				loop.awaitWakeUp();
				awakened.countDown();
			});
			thread.start();

			wakeBus.publish(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE, segment1PotId);
			assertTrue(!awakened.await(100, TimeUnit.MILLISECONDS));

			wakeBus.publish(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE, segment0PotId);
			assertTrue(awakened.await(500, TimeUnit.MILLISECONDS));
			thread.join(500);
		}
	}

	@Test
	void wakesWhenTimeoutIsReachedWithoutSignal() throws InterruptedException {
		CountDownLatch awakened = new CountDownLatch(1);

		try (WakeablePollingLoop loop = new WakeablePollingLoop(
				ProjectionWorkerWakeBus.noop(),
				Set.of(ProjectionWorkerWakeSignal.PROJECTION_TASKS_AVAILABLE),
				Duration.ofMillis(50),
				false)) {
			Thread thread = new Thread(() -> {
				loop.awaitWakeUp();
				awakened.countDown();
			});
			thread.start();

			assertTrue(awakened.await(1, TimeUnit.SECONDS));
			thread.join(500);
		}
	}

	private static PotId potIdForSegment(int segmentIndex, int segmentCount) {
		for (int index = 0; index < 1_000; index++) {
			PotId potId = PotId.of(UUID.nameUUIDFromBytes(
					("wake-pot-" + segmentIndex + "-" + index).getBytes(StandardCharsets.UTF_8)));
			if (PotPartitioner.segmentOf(potId, segmentCount) == segmentIndex) {
				return potId;
			}
		}
		throw new IllegalStateException("No potId found for segment " + segmentIndex);
	}
}
