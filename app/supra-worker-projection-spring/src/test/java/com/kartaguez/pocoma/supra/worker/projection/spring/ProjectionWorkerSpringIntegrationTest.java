package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
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
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.port.in.projection.intent.BuildProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeEvent;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSubscription;

class ProjectionWorkerSpringIntegrationTest {

	@Test
	void publishedSpringEventWakesTaskBuilderWithoutSubmittingProjectionDirectly() {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(ComputePotBalancesUseCase.class, RecordingComputePotBalancesUseCase::new)
				.withBean(ProjectionWorkerWakeBus.class, () -> wakeBus)
				.withUserConfiguration(ProjectionWorkerConfiguration.class, TestConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.event-listener-enabled=true",
						"pocoma.projection.worker.task-builder-enabled=false",
						"pocoma.projection.worker.task-executor-enabled=false",
						"pocoma.projection.worker.thread-count=1",
						"pocoma.projection.worker.initial-backoff=0ms",
						"pocoma.projection.worker.max-backoff=0ms");

		contextRunner.run(context -> {
			PotId potId = PotId.of(UUID.randomUUID());
			context.publishEvent(new PotCreatedEvent(potId, 12));

			assertTrue(wakeBus.await());
			assertEquals(ProjectionWorkerWakeSignal.BUSINESS_EVENTS_AVAILABLE, wakeBus.signal);
		});
	}

	@Test
	void exposesSpringLifecycleAdapterForCoreWorker() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(ComputePotBalancesUseCase.class, RecordingComputePotBalancesUseCase::new)
				.withUserConfiguration(ProjectionWorkerConfiguration.class, TestConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.task-builder-enabled=false",
						"pocoma.projection.worker.task-executor-enabled=false",
						"pocoma.projection.worker.thread-count=1",
						"pocoma.projection.worker.initial-backoff=0ms",
						"pocoma.projection.worker.max-backoff=0ms");

		contextRunner.run(context -> assertTrue(context.getBean(SpringProjectionTaskExecutorLifecycle.class) instanceof SmartLifecycle));
	}

	@Configuration
	static class TestConfiguration {

		@Bean
		Object listenerMarker() {
			return new Object();
		}

		@Bean
		BuildProjectionTasksUseCase buildProjectionTasksUseCase() {
			return new NoopBuildProjectionTasksUseCase();
		}

		@Bean
		ExecuteProjectionTasksUseCase executeProjectionTasksUseCase(ComputePotBalancesUseCase computePotBalancesUseCase) {
			return new NoopExecuteProjectionTasksUseCase(computePotBalancesUseCase);
		}
	}

	private static final class NoopBuildProjectionTasksUseCase implements BuildProjectionTasksUseCase {

		@Override
		public java.util.List<BusinessEventClaim> claimBusinessEvents(
				int limit,
				java.time.Duration leaseDuration,
				String workerId) {
			return java.util.List.of();
		}

		@Override
		public boolean markAccepted(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markRunning(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public void buildProjectionTask(BuildProjectionTaskCommand command) {
		}

		@Override
		public boolean markDone(UUID eventId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markFailed(UUID eventId, UUID claimToken, String error) {
			return true;
		}

		@Override
		public boolean release(UUID eventId, UUID claimToken) {
			return true;
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

	private static final class NoopExecuteProjectionTasksUseCase implements ExecuteProjectionTasksUseCase {

		private final ComputePotBalancesUseCase computePotBalancesUseCase;

		private NoopExecuteProjectionTasksUseCase(ComputePotBalancesUseCase computePotBalancesUseCase) {
			this.computePotBalancesUseCase = computePotBalancesUseCase;
		}

		@Override
		public java.util.List<ProjectionTaskClaim> claimProjectionTasks(
				int limit,
				java.time.Duration leaseDuration,
				String workerId) {
			return java.util.List.of();
		}

		@Override
		public boolean markAccepted(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markRunning(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
			computePotBalancesUseCase.computePotBalances(command.potId(), command.targetVersion());
		}

		@Override
		public boolean markDone(UUID taskId, UUID claimToken) {
			return true;
		}

		@Override
		public boolean markFailed(UUID taskId, UUID claimToken, String error) {
			return true;
		}

		@Override
		public boolean release(UUID taskId, UUID claimToken) {
			return true;
		}
	}

	private static final class RecordingWakeBus implements ProjectionWorkerWakeBus {
		private final CountDownLatch published = new CountDownLatch(1);
		private ProjectionWorkerWakeSignal signal;

		@Override
		public void publish(ProjectionWorkerWakeEvent event) {
			this.signal = event.signal();
			published.countDown();
		}

		@Override
		public ProjectionWorkerWakeSubscription subscribe(
				Set<ProjectionWorkerWakeSignal> signals,
				ProjectionPartition partition,
				Runnable listener) {
			return () -> {
			};
		}

		private boolean await() throws InterruptedException {
			return published.await(2, TimeUnit.SECONDS);
		}
	}
}
