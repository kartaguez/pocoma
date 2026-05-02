package com.kartaguez.pocoma.supra.worker.projection.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.BuildProjectionTasksUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder.BusinessEventWorkSource;
import com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder.ProjectionTaskBuilderWorker;

@Configuration
@EnableConfigurationProperties({ ProjectionTaskBuilderWorkerProperties.class, ProjectionNatsProperties.class })
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "mode", havingValue = "polling", matchIfMissing = true)
@ConditionalOnProperty(
		prefix = "pocoma.projection.worker",
		name = "task-builder-enabled",
		havingValue = "true",
		matchIfMissing = true)
public class ProjectionTaskBuilderWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean(WorkWakeBus.class)
	WorkWakeBus<String, PotId> projectionTaskBuilderWakeBus() {
		return new InMemoryWorkWakeBus<>();
	}

	@Bean
	@ConditionalOnMissingBean
	BusinessEventWorkSource businessEventWorkSource(BusinessEventOutboxPort outboxPort) {
		return new BusinessEventWorkSource(outboxPort);
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionTaskBuilderWorker projectionTaskBuilderWorker(
			BusinessEventWorkSource businessEventWorkSource,
			BuildProjectionTasksUseCase buildProjectionTasksUseCase,
			ProjectionTaskBuilderWorkerProperties properties,
			WorkWakeBus<String, PotId> wakeBus) {
		return new ProjectionTaskBuilderWorker(
				businessEventWorkSource,
				buildProjectionTasksUseCase,
				properties.toTaskBuilderSettings(),
				wakeBus,
				potId -> PotPartitioner.belongsTo(potId, properties.toTaskBuilderSettings().partition()));
	}

	@Bean
	@ConditionalOnMissingBean(name = "projectionTaskBuilderLifecycle")
	ProjectionTaskBuilderPollingWorkerLifecycle projectionTaskBuilderLifecycle(ProjectionTaskBuilderWorker worker) {
		return new ProjectionTaskBuilderPollingWorkerLifecycle(
				worker::start,
				worker::stop,
				worker::isRunning,
				Integer.MAX_VALUE - 50);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "pocoma.projection.worker",
			name = "event-listener-enabled",
			havingValue = "true")
	@ConditionalOnMissingBean
	ProjectionEventListener projectionEventListener(WorkWakeBus<String, PotId> wakeBus) {
		return new ProjectionEventListener(wakeBus);
	}

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper projectionWorkerObjectMapper() {
		return new ObjectMapper();
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	NatsWakeClient natsWakeClient(ProjectionNatsProperties properties) throws Exception {
		return JnatsWakeClient.connect(properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	NatsBusinessEventsWakeSubscriber natsBusinessEventsWakeSubscriber(
			NatsWakeClient natsClient,
			ProjectionNatsProperties properties,
			ObjectMapper objectMapper,
			WorkWakeBus<String, PotId> wakeBus) {
		return new NatsBusinessEventsWakeSubscriber(natsClient, properties, objectMapper, wakeBus);
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.projection.nats", name = "enabled", havingValue = "true", matchIfMissing = true)
	@ConditionalOnMissingBean
	NatsProjectionTasksReadyPublisher natsProjectionTasksReadyPublisher(
			NatsWakeClient natsClient,
			ProjectionNatsProperties properties,
			ObjectMapper objectMapper) {
		return new NatsProjectionTasksReadyPublisher(natsClient, properties, objectMapper);
	}
}
