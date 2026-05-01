package com.kartaguez.pocoma.supra.worker.projection.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder.ProjectionTaskBuilderWorker;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorWorker;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.SegmentedProjectionTaskExecutor;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.InMemoryProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.wakeup.ProjectionWorkerWakeSignal;

@Configuration
@EnableConfigurationProperties(ProjectionWorkerProperties.class)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProjectionWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean
	PocomaObservation pocomaObservation() {
		return new NoopPocomaObservation();
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionWorkerWakeBus projectionWorkerWakeBus() {
		return new InMemoryProjectionWorkerWakeBus();
	}

	@Bean
	@ConditionalOnMissingBean
	SegmentedProjectionTaskExecutor segmentedProjectionTaskExecutor(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionWorkerProperties properties,
			PocomaObservation observation,
			ProjectionWorkerWakeBus wakeBus) {
		return new SegmentedProjectionTaskExecutor(
				executeProjectionTasksUseCase,
				properties.toSettings(),
				observation,
				potId -> wakeBus.publish(ProjectionWorkerWakeSignal.CAPACITY_AVAILABLE, potId));
	}

	@Bean
	@ConditionalOnMissingBean
	SpringProjectionTaskExecutorLifecycle springProjectionTaskExecutorLifecycle(
			SegmentedProjectionTaskExecutor executor) {
		return new SpringProjectionTaskExecutorLifecycle(executor);
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionTaskBuilderWorker projectionTaskBuilderWorker(
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionWorkerProperties properties,
			ProjectionWorkerWakeBus wakeBus) {
		return new ProjectionTaskBuilderWorker(buildProjectionTasksUseCase, properties.toTaskBuilderSettings(), wakeBus);
	}

	@Bean
	@ConditionalOnMissingBean(name = "projectionTaskBuilderLifecycle")
	SpringPollingWorkerLifecycle projectionTaskBuilderLifecycle(ProjectionTaskBuilderWorker worker) {
		return new SpringPollingWorkerLifecycle(
				worker::start,
				worker::stop,
				worker::isRunning,
				Integer.MAX_VALUE - 50);
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionTaskExecutorWorker projectionTaskExecutorWorker(
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			SegmentedProjectionTaskExecutor worker,
			ProjectionWorkerProperties properties,
			ProjectionWorkerWakeBus wakeBus) {
		return new ProjectionTaskExecutorWorker(
				executeProjectionTasksUseCase,
				worker,
				properties.toTaskExecutorSettings(),
				wakeBus);
	}

	@Bean
	@ConditionalOnMissingBean(name = "projectionTaskExecutorLifecycle")
	SpringPollingWorkerLifecycle projectionTaskExecutorLifecycle(ProjectionTaskExecutorWorker worker) {
		return new SpringPollingWorkerLifecycle(
				worker::start,
				worker::stop,
				worker::isRunning,
				Integer.MAX_VALUE);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "pocoma.projection.worker",
			name = "event-listener-enabled",
			havingValue = "true")
	@ConditionalOnMissingBean
	ProjectionEventListener projectionEventListener(ProjectionWorkerWakeBus wakeBus) {
		return new ProjectionEventListener(wakeBus);
	}
}
