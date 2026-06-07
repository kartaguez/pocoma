package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.task.PipelineId;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineRegistry;
import com.kartaguez.pocoma.engine.taskmaterialization.model.PipelineStrategy;
import com.kartaguez.pocoma.domain.pipeline.task.TaskDescriptor;
import com.kartaguez.pocoma.engine.taskmaterialization.port.out.TaskMaterializationPort;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.EventToPipelineTaskMaterializerWorker;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.MaterializableEventSource;

class PipelineMaterializationConfigurationTest {

	private static final PipelineDefinition DEFINITION = new PipelineDefinition(PipelineId.of("test-pipeline"), 1);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PipelineMaterializationConfiguration.class))
			.withBean(TaskMaterializationPort.class, RecordingPort::new)
			.withBean(MaterializableEventSource.class, RecordingEventSource::new)
			.withBean(PipelineStrategy.class, () -> new TestStrategy(DEFINITION));

	@Test
	void backsOffWhenPipelineMaterializationIsDisabled() {
		contextRunner
				.withPropertyValues("pocoma.pipeline.materialization.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean(EventToPipelineTaskMaterializerWorker.class));
	}

	@Test
	void createsWorkerAndConfiguredRegistryWhenEnabled() {
		contextRunner
				.withPropertyValues(
						"pocoma.pipeline.materialization.enabled=true",
						"pocoma.pipeline.materialization.worker-id=worker-a",
						"pocoma.pipeline.materialization.batch-size=25",
						"pocoma.pipeline.materialization.polling-interval=500ms",
						"pocoma.pipeline.materialization.safety-delay=5ms",
						"pocoma.pipeline.materialization.segment-index=1",
						"pocoma.pipeline.materialization.segment-count=3",
						"pocoma.pipeline.materialization.pipelines[0].pipeline-id=test-pipeline",
						"pocoma.pipeline.materialization.pipelines[0].pipeline-version=1",
						"pocoma.pipeline.materialization.pipelines[0].enabled=true",
						"pocoma.pipeline.materialization.pipelines[0].event-types[0]=PotCreatedEvent")
				.run(context -> {
					assertThat(context).hasSingleBean(EventToPipelineTaskMaterializerWorker.class);
					PipelineRegistry registry = context.getBean(PipelineRegistry.class);
					assertThat(registry.activeBindings()).containsExactly(new ConfiguredPipelineBinding(
							DEFINITION,
							List.of("PotCreatedEvent"),
							true));
					PipelineMaterializerWorkerProperties properties = context.getBean(
							PipelineMaterializerWorkerProperties.class);
					assertThat(properties.toSettings().batchSize()).isEqualTo(25);
					assertThat(properties.toSettings().partition()).isEqualTo(new ProjectionPartition(1, 3));
				});
	}

	private record TestStrategy(PipelineDefinition definition) implements PipelineStrategy {
		@Override
		public boolean supports(BusinessEventEnvelope event) {
			return true;
		}

		@Override
		public List<TaskDescriptor> materializeTasks(BusinessEventEnvelope event) {
			return List.of();
		}
	}

	private static final class RecordingPort implements TaskMaterializationPort {
		@Override
		public MaterializationResult materialize(
				EventPipelineMaterializationCandidate candidate,
				List<TaskDescriptor> tasks) {
			return MaterializationResult.materialized(candidate, tasks.size());
		}

		@Override
		public MaterializationResult markSkipped(EventPipelineMaterializationCandidate candidate) {
			return MaterializationResult.skipped(candidate);
		}

		@Override
		public MaterializationResult markFailed(
				EventPipelineMaterializationCandidate candidate,
				String failureKind,
				RuntimeException error) {
			return MaterializationResult.failed(candidate);
		}

	}

	private static final class RecordingEventSource implements MaterializableEventSource {
		@Override
		public List<EventPipelineMaterializationCandidate> findUnmaterializedEventPipelinePairs(
				int limit,
				ProjectionPartition partition,
				Instant upperBound,
				List<ConfiguredPipelineBinding> activeBindings) {
			return List.of();
		}

		@Override
		public long countUnmaterialized(List<ConfiguredPipelineBinding> activeBindings) {
			return 0;
		}
	}
}
