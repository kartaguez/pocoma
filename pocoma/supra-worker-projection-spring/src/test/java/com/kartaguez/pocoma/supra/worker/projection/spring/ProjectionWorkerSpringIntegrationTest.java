package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;

class ProjectionWorkerSpringIntegrationTest {

	@Test
	void publishedSpringEventTriggersProjectionComputation() {
		RecordingComputePotBalancesUseCase computeUseCase = new RecordingComputePotBalancesUseCase();
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(ComputePotBalancesUseCase.class, () -> computeUseCase)
				.withUserConfiguration(ProjectionWorkerConfiguration.class, TestConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.thread-count=1",
						"pocoma.projection.worker.initial-backoff=0ms",
						"pocoma.projection.worker.max-backoff=0ms");

		contextRunner.run(context -> {
			PotId potId = PotId.of(UUID.randomUUID());
			context.publishEvent(new PotCreatedEvent(potId, 12));

			assertTrue(computeUseCase.await());
			assertEquals(potId, computeUseCase.potId);
			assertEquals(12, computeUseCase.version.get());
		});
	}

	@Test
	void exposesSpringLifecycleAdapterForCoreWorker() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(ComputePotBalancesUseCase.class, RecordingComputePotBalancesUseCase::new)
				.withUserConfiguration(ProjectionWorkerConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.thread-count=1",
						"pocoma.projection.worker.initial-backoff=0ms",
						"pocoma.projection.worker.max-backoff=0ms");

		contextRunner.run(context -> assertTrue(context.getBean(SpringProjectionWorkerLifecycle.class) instanceof SmartLifecycle));
	}

	@Configuration
	static class TestConfiguration {

		@Bean
		Object listenerMarker() {
			return new Object();
		}
	}

	private static final class RecordingComputePotBalancesUseCase implements ComputePotBalancesUseCase {
		private final CountDownLatch called = new CountDownLatch(1);
		private final AtomicLong version = new AtomicLong();
		private PotId potId;

		@Override
		public PotBalances computePotBalances(PotId potId, long targetVersion) {
			this.potId = potId;
			this.version.set(targetVersion);
			called.countDown();
			return new PotBalances(potId, targetVersion, Map.of());
		}

		private boolean await() throws InterruptedException {
			return called.await(2, TimeUnit.SECONDS);
		}
	}
}
