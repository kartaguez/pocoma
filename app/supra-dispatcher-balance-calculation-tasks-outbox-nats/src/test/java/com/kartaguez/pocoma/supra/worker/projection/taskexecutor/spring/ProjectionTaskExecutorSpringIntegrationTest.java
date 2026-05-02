package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.projection.ProjectionTasksReadyEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.model.ProjectionTaskClaim;
import com.kartaguez.pocoma.engine.model.ProjectionTaskDescriptor;
import com.kartaguez.pocoma.engine.port.in.projection.intent.ExecuteProjectionTaskCommand;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeEvent;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeSubscription;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWakeSignals;

class ProjectionTaskExecutorSpringIntegrationTest {

	@Test
	void projectionTasksReadyEventWakesTaskExecutor() {
		RecordingWakeBus wakeBus = new RecordingWakeBus();
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(WorkWakeBus.class, () -> wakeBus)
				.withUserConfiguration(ProjectionTaskExecutorWorkerConfiguration.class, TestConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.task-executor-enabled=true",
						"pocoma.projection.nats.enabled=false",
						"pocoma.projection.worker.thread-count=1",
						"pocoma.projection.worker.initial-backoff=0ms",
						"pocoma.projection.worker.max-backoff=0ms");

		contextRunner.run(context -> {
			PotId potId = PotId.of(UUID.randomUUID());
			context.publishEvent(new ProjectionTasksReadyEvent(UUID.randomUUID(), potId, 12, "PotCreatedEvent"));

			assertTrue(wakeBus.await());
			assertEquals(ProjectionWakeSignals.PROJECTION_TASKS_AVAILABLE, wakeBus.signal);
		});
	}

	@Test
	void exposesSpringLifecycleAdapterForCoreWorker() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withUserConfiguration(ProjectionTaskExecutorWorkerConfiguration.class, TestConfiguration.class)
				.withPropertyValues(
						"pocoma.projection.worker.task-executor-enabled=true",
						"pocoma.projection.nats.enabled=false",
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
		ExecuteProjectionTasksUseCase executeProjectionTasksUseCase() {
			return new NoopExecuteProjectionTasksUseCase();
		}

		@Bean
		ProjectionTaskPort projectionTaskPort() {
			return new NoopProjectionTaskPort();
		}

		@Bean
		ProjectionEventPublisherPort projectionEventPublisherPort() {
			return ProjectionEventPublisherPort.noop();
		}
	}

	private static final class NoopExecuteProjectionTasksUseCase implements ExecuteProjectionTasksUseCase {

		@Override
		public void executeProjectionTask(ExecuteProjectionTaskCommand command) {
		}
	}

	private static final class NoopProjectionTaskPort implements ProjectionTaskPort {

		@Override
		public ProjectionTaskDescriptor upsertComputeBalancesTask(BusinessEventEnvelope sourceEvent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public List<ProjectionTaskClaim> claimPending(
				int limit,
				Duration leaseDuration,
				String workerId,
				ProjectionPartition partition) {
			return List.of();
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

		@Override
		public boolean heartbeat(UUID taskId, UUID claimToken, Duration leaseDuration) {
			return false;
		}

		@Override
		public long countPendingOrInProgress() {
			return 0;
		}

		@Override
		public long countPendingOrInProgress(PotId potId) {
			return 0;
		}
	}

	private static final class RecordingWakeBus implements WorkWakeBus<String, PotId> {
		private final CountDownLatch published = new CountDownLatch(1);
		private String signal;

		@Override
		public void publish(WorkWakeEvent<String, PotId> event) {
			this.signal = event.signal();
			published.countDown();
		}

		@Override
		public WorkWakeSubscription subscribe(
				Set<String> signals,
				Predicate<PotId> keyPredicate,
				Runnable listener) {
			return () -> {
			};
		}

		private boolean await() throws InterruptedException {
			return published.await(2, TimeUnit.SECONDS);
		}
	}
}
