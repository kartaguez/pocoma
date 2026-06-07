package com.kartaguez.pocoma.supra.worker.pipelinetask.spring;

import java.util.Collection;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionRegistry;
import com.kartaguez.pocoma.engine.taskexecution.model.PipelineTaskExecutionStrategy;
import com.kartaguez.pocoma.engine.taskexecution.port.in.ExecutePipelineTaskUseCase;
import com.kartaguez.pocoma.engine.taskexecution.service.ExecutePipelineTaskService;
import com.kartaguez.pocoma.orchestrator.claimable.wake.CapacityNotifier;
import com.kartaguez.pocoma.orchestrator.claimable.wake.InMemoryWorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.NoopPipelineTaskExecutionObservation;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutionObservation;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutorWorker;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskWakeSignals;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskWorkSource;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.SegmentedPipelineTaskExecutor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties(PipelineTaskExecutorWorkerProperties.class)
@ConditionalOnProperty(prefix = "pocoma.pipeline.task-execution", name = "enabled", havingValue = "true")
public class PipelineTaskExecutorConfiguration {

	@Bean
	@ConditionalOnMissingBean(WorkWakeBus.class)
	WorkWakeBus<String, PotId> pipelineTaskExecutorWakeBus() {
		return new InMemoryWorkWakeBus<>();
	}

	@Bean
	@ConditionalOnMissingBean
	PipelineTaskExecutionRegistry pipelineTaskExecutionRegistry(
			ObjectProvider<PipelineTaskExecutionStrategy> strategies,
			PipelineTaskExecutorWorkerProperties properties) {
		Collection<PipelineTaskExecutionStrategy> availableStrategies = strategies.orderedStream().toList();
		return new PipelineTaskExecutionRegistry(availableStrategies, properties.toBindings());
	}

	@Bean
	@ConditionalOnMissingBean
	ExecutePipelineTaskUseCase executePipelineTaskUseCase(PipelineTaskExecutionRegistry registry) {
		return new ExecutePipelineTaskService(registry);
	}

	@Bean
	@ConditionalOnMissingBean
	SegmentedPipelineTaskExecutor segmentedPipelineTaskExecutor(
			PipelineTaskWorkSource workSource,
			ExecutePipelineTaskUseCase useCase,
			PipelineTaskExecutorWorkerProperties properties,
			WorkWakeBus<String, PotId> wakeBus,
			ObjectProvider<PipelineTaskExecutionObservation> observation) {
		CapacityNotifier<PotId> capacityNotifier = new CapacityNotifier<>(
				wakeBus,
				PipelineTaskWakeSignals.CAPACITY_AVAILABLE,
				properties.getCapacityWakeupMinInterval());
		return new SegmentedPipelineTaskExecutor(
				workSource,
				useCase,
				properties.toExecutorSettings(),
				observation.getIfAvailable(NoopPipelineTaskExecutionObservation::new),
				capacityNotifier);
	}

	@Bean
	@ConditionalOnMissingBean
	PipelineTaskExecutorWorker pipelineTaskExecutorWorker(
			PipelineTaskWorkSource workSource,
			SegmentedPipelineTaskExecutor executor,
			PipelineTaskExecutionRegistry registry,
			PipelineTaskExecutorWorkerProperties properties,
			WorkWakeBus<String, PotId> wakeBus,
			ObjectProvider<PipelineTaskExecutionObservation> observation) {
		return new PipelineTaskExecutorWorker(
				workSource,
				executor,
				registry,
				properties.toWorkerSettings(),
				wakeBus,
				potId -> PotPartitioner.belongsTo(potId, properties.toWorkerSettings().partition()),
				observation.getIfAvailable(NoopPipelineTaskExecutionObservation::new));
	}

	@Bean
	@ConditionalOnMissingBean(name = "pipelineTaskExecutorLifecycle")
	PipelineTaskExecutorLifecycle pipelineTaskExecutorLifecycle(PipelineTaskExecutorWorker worker) {
		return new PipelineTaskExecutorLifecycle(
				worker::start,
				worker::stop,
				worker::isRunning,
				Integer.MAX_VALUE - 50);
	}

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	PipelineTaskExecutionObservation pipelineTaskExecutionObservation(
			MeterRegistry meterRegistry,
			PipelineTaskExecutorWorkerProperties properties) {
		return new MicrometerPipelineTaskExecutionObservation(meterRegistry, properties);
	}

	@Bean
	@ConditionalOnClass(MeterRegistry.class)
	@ConditionalOnBean(MeterRegistry.class)
	PipelineTaskBacklogMetrics pipelineTaskBacklogMetrics(
			MeterRegistry meterRegistry,
			PipelineTaskWorkSource workSource,
			PipelineTaskExecutorWorker worker,
			PipelineTaskExecutorWorkerProperties properties) {
		PipelineTaskBacklogMetrics metrics = new PipelineTaskBacklogMetrics(workSource, worker);
		Gauge.builder("pocoma.pipeline_task_execution.tasks.pending", metrics,
				PipelineTaskBacklogMetrics::pendingOrInProgress)
				.description("Number of pipeline tasks pending, claimed, accepted, or running for active bindings.")
				.tags(workerTags(properties))
				.register(meterRegistry);
		Gauge.builder("pocoma.pipeline_task_execution.tasks.failed", metrics,
				PipelineTaskBacklogMetrics::failed)
				.description("Number of failed pipeline tasks for active bindings.")
				.tags(workerTags(properties))
				.register(meterRegistry);
		return metrics;
	}

	private static String[] workerTags(PipelineTaskExecutorWorkerProperties properties) {
		return new String[] {
				"worker_id", properties.getWorkerId(),
				"segment_index", Integer.toString(properties.getSegmentIndex()),
				"segment_count", Integer.toString(properties.getSegmentCount())
		};
	}
}
