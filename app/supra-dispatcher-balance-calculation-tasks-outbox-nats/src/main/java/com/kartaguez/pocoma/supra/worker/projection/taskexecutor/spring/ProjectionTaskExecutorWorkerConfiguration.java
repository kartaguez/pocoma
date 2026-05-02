package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ExecuteProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.event.ProjectionEventPublisherPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorWorker;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskWorkSource;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.SegmentedProjectionTaskExecutor;

@Configuration
@EnableConfigurationProperties({ ProjectionTaskExecutorWorkerProperties.class, ProjectionTaskExecutorNatsProperties.class })
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "mode", havingValue = "polling", matchIfMissing = true)
@ConditionalOnProperty(
		prefix = "pocoma.projection.worker",
		name = "task-executor-enabled",
		havingValue = "true",
		matchIfMissing = true)
public class ProjectionTaskExecutorWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean
	PocomaObservation pocomaObservation() {
		return new NoopPocomaObservation();
	}

	@Bean
	@ConditionalOnMissingBean(WorkWakeBus.class)
	WorkWakeBus<String, PotId> projectionTaskExecutorWakeBus() {
		return new InMemoryWorkWakeBus<>();
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionTaskWorkSource projectionTaskWorkSource(
			ProjectionTaskPort projectionTaskPort,
			ProjectionEventPublisherPort eventPublisherPort) {
		return new ProjectionTaskWorkSource(projectionTaskPort, eventPublisherPort);
	}

	@Bean
	@ConditionalOnMissingBean
	SegmentedProjectionTaskExecutor segmentedProjectionTaskExecutor(
			ProjectionTaskWorkSource projectionTaskWorkSource,
			ExecuteProjectionTasksUseCase executeProjectionTasksUseCase,
			ProjectionTaskExecutorWorkerProperties properties,
			PocomaObservation observation) {
		return new SegmentedProjectionTaskExecutor(
				projectionTaskWorkSource,
				executeProjectionTasksUseCase,
				properties.toSettings(),
				observation);
	}

	@Bean
	@ConditionalOnMissingBean
	SpringProjectionTaskExecutorLifecycle springProjectionTaskExecutorLifecycle(
			SegmentedProjectionTaskExecutor executor) {
		return new SpringProjectionTaskExecutorLifecycle(executor);
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionTaskExecutorWorker projectionTaskExecutorWorker(
			ProjectionTaskWorkSource projectionTaskWorkSource,
			SegmentedProjectionTaskExecutor worker,
			ProjectionTaskExecutorWorkerProperties properties,
			WorkWakeBus<String, PotId> wakeBus) {
		return new ProjectionTaskExecutorWorker(
				projectionTaskWorkSource,
				worker,
				properties.toTaskExecutorSettings(),
				wakeBus,
				potId -> PotPartitioner.belongsTo(potId, properties.toTaskExecutorSettings().partition()));
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
	@ConditionalOnMissingBean
	ProjectionTaskExecutorEventListener projectionTaskExecutorEventListener(WorkWakeBus<String, PotId> wakeBus) {
		return new ProjectionTaskExecutorEventListener(wakeBus);
	}

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper projectionTaskExecutorObjectMapper() {
		return new ObjectMapper();
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	ProjectionTaskExecutorNatsWakeClient projectionTaskExecutorNatsWakeClient(
			ProjectionTaskExecutorNatsProperties properties) throws Exception {
		return JnatsProjectionTaskExecutorWakeClient.connect(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	NatsProjectionTasksWakeSubscriber natsProjectionTasksWakeSubscriber(
			ProjectionTaskExecutorNatsWakeClient natsClient,
			ProjectionTaskExecutorNatsProperties properties,
			ObjectMapper objectMapper,
			WorkWakeBus<String, PotId> wakeBus) {
		return new NatsProjectionTasksWakeSubscriber(natsClient, properties, objectMapper, wakeBus);
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	NatsProjectionTaskProcessedPublisher natsProjectionTaskProcessedPublisher(
			ProjectionTaskExecutorNatsWakeClient natsClient,
			ProjectionTaskExecutorNatsProperties properties,
			ObjectMapper objectMapper) {
		return new NatsProjectionTaskProcessedPublisher(natsClient, properties, objectMapper);
	}
}
