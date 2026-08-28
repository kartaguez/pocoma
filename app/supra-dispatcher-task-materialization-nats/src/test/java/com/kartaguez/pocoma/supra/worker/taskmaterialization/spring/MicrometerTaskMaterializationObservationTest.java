package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.legacy.event.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationEventObservation;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationRunObservation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MicrometerTaskMaterializationObservationTest {

	@Test
	void recordsRunAndMaterializationMetrics() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		MicrometerTaskMaterializationObservation observation = new MicrometerTaskMaterializationObservation(meterRegistry);
		EventPipelineMaterializationCandidate candidate = new EventPipelineMaterializationCandidate(
				event(Instant.parse("2026-06-07T12:00:00Z")),
				new PipelineDefinition(PipelineId.of("balance-projection"), 1));

		observation.runCompleted(new TaskMaterializationRunObservation(
				"worker-a",
				new ProjectionPartition(1, 3),
				2,
				3,
				1_000_000));
		observation.materializationCompleted(
				new TaskMaterializationEventObservation(
						candidate,
						Instant.parse("2026-06-07T12:00:02Z"),
						2_000_000),
				MaterializationResult.materialized(candidate, 2));

		assertThat(meterRegistry.get("pocoma.task_materialization.run.duration")
				.tag("worker_id", "worker-a")
				.timer()
				.count()).isEqualTo(1);
		assertThat(meterRegistry.get("pocoma.task_materialization.candidates.selected.total")
				.tag("segment_index", "1")
				.counter()
				.count()).isEqualTo(3.0);
		assertThat(meterRegistry.get("pocoma.task_materialization.materializations.total")
				.tag("pipeline_id", "balance-projection")
				.tag("event_type", "POT_CREATED")
				.tag("outcome", "MATERIALIZED")
				.counter()
				.count()).isEqualTo(1.0);
		assertThat(meterRegistry.get("pocoma.task_materialization.tasks.created.total")
				.tag("pipeline_version", "1")
				.counter()
				.count()).isEqualTo(2.0);
		assertThat(meterRegistry.get("pocoma.task_materialization.event.lag")
				.timer()
				.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2.0);
	}

	private static BusinessEventEnvelope event(Instant createdAt) {
		PotId potId = PotId.of(UUID.randomUUID());
		return new BusinessEventEnvelope(
				UUID.randomUUID(),
				"POT_CREATED",
				potId,
				potId.value(),
				1,
				"{}",
				null,
				null,
				createdAt);
	}
}
