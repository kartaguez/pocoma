package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import java.time.Clock;
import java.util.Collection;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.PotPartitioner;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationOutcome;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineStrategy;
import com.kartaguez.pocoma.engine.taskmaterialization.port.in.MaterializeTasksUseCase;
import com.kartaguez.pocoma.engine.taskmaterialization.port.out.TaskMaterializationPort;
import com.kartaguez.pocoma.engine.taskmaterialization.service.MaterializeTasksService;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.dispatcher.projection.shared.wakeup.ProjectionWakeSignals;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.EventToPipelineTaskMaterializerWorker;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.MaterializableEventSource;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.NoopTaskMaterializationObservation;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.PipelineMaterializationCompletedListener;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationObservation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties({
		PipelineMaterializerWorkerProperties.class,
		TaskMaterializationNatsProperties.class
})
@ConditionalOnProperty(prefix = "pocoma.pipeline.materialization", name = "enabled", havingValue = "true")
public class PipelineMaterializationConfiguration {

	private static final String PIPELINE_TASKS_AVAILABLE = "PIPELINE_TASKS_AVAILABLE";

	@Bean
	@ConditionalOnMissingBean(WorkWakeBus.class)
	WorkWakeBus<String, PotId> pipelineMaterializerWakeBus() {
		return new InMemoryWorkWakeBus<>();
	}

	@Bean
	@ConditionalOnMissingBean
	PipelineRegistry pipelineRegistry(
			ObjectProvider<PipelineStrategy> strategies,
			PipelineMaterializerWorkerProperties properties) {
		Collection<PipelineStrategy> availableStrategies = strategies.orderedStream().toList();
		return new PipelineRegistry(availableStrategies, properties.toBindings());
	}

	@Bean
	@ConditionalOnMissingBean
	MaterializeTasksUseCase materializeTasksUseCase(
			PipelineRegistry pipelineRegistry,
			TaskMaterializationPort materializationPort) {
		return new MaterializeTasksService(pipelineRegistry, materializationPort);
	}

	@Bean
	@ConditionalOnMissingBean
	PipelineMaterializationCompletedListener pipelineMaterializationCompletedListener(
			WorkWakeBus<String, PotId> wakeBus) {
		return (candidate, result) -> {
			if (result.outcome() == MaterializationOutcome.MATERIALIZED && result.taskCount() > 0) {
				wakeBus.publish(PIPELINE_TASKS_AVAILABLE, candidate.event().potId());
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean
	EventToPipelineTaskMaterializerWorker eventToPipelineTaskMaterializerWorker(
			MaterializableEventSource eventSource,
			MaterializeTasksUseCase materializeTasksUseCase,
			PipelineRegistry pipelineRegistry,
			PipelineMaterializerWorkerProperties properties,
			WorkWakeBus<String, PotId> wakeBus,
			PipelineMaterializationCompletedListener completedListener,
			ObjectProvider<TaskMaterializationObservation> observation) {
		return new EventToPipelineTaskMaterializerWorker(
				eventSource,
				materializeTasksUseCase,
				pipelineRegistry,
				properties.toSettings(),
				Clock.systemUTC(),
				wakeBus,
				potId -> PotPartitioner.belongsTo(potId, properties.toSettings().partition()),
				completedListener,
				observation.getIfAvailable(NoopTaskMaterializationObservation::new));
	}

	@Bean
	@ConditionalOnMissingBean(name = "pipelineMaterializerLifecycle")
	TaskMaterializerPollingWorkerLifecycle pipelineMaterializerLifecycle(
			EventToPipelineTaskMaterializerWorker worker) {
		return new TaskMaterializerPollingWorkerLifecycle(
				worker::start,
				worker::stop,
				worker::isRunning,
				Integer.MAX_VALUE - 60);
	}

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper taskMaterializationObjectMapper() {
		return new ObjectMapper();
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "pocoma.task-materialization.nats", name = "enabled", havingValue = "true")
	NatsWakeClient taskMaterializationNatsWakeClient(TaskMaterializationNatsProperties properties)
			throws java.io.IOException, InterruptedException {
		return JnatsWakeClient.connect(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = "pocoma.task-materialization.nats", name = "enabled", havingValue = "true")
	NatsBusinessEventsWakeSubscriber taskMaterializationBusinessEventsWakeSubscriber(
			NatsWakeClient natsClient,
			TaskMaterializationNatsProperties properties,
			ObjectMapper objectMapper,
			WorkWakeBus<String, PotId> wakeBus) {
		return new NatsBusinessEventsWakeSubscriber(natsClient, properties, objectMapper, wakeBus);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	TaskMaterializationObservation taskMaterializationObservation(MeterRegistry meterRegistry) {
		return new MicrometerTaskMaterializationObservation(meterRegistry);
	}

	@Bean
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	TaskMaterializationBacklogMetrics taskMaterializationBacklogMetrics(
			MeterRegistry meterRegistry,
			MaterializableEventSource eventSource,
			PipelineRegistry pipelineRegistry) {
		TaskMaterializationBacklogMetrics metrics = new TaskMaterializationBacklogMetrics(eventSource, pipelineRegistry);
		Gauge.builder("pocoma.task_materialization.unmaterialized.events", metrics,
				TaskMaterializationBacklogMetrics::countUnmaterialized)
				.description("Number of event pipeline pairs not yet materialized for active pipelines.")
				.register(meterRegistry);
		return metrics;
	}
}
