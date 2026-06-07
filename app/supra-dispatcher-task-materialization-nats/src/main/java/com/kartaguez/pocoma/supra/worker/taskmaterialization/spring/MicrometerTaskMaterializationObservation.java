package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.kartaguez.pocoma.engine.taskmaterialization.model.EventPipelineMaterializationCandidate;
import com.kartaguez.pocoma.engine.taskmaterialization.model.MaterializationResult;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationEventObservation;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationObservation;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.TaskMaterializationRunObservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

final class MicrometerTaskMaterializationObservation implements TaskMaterializationObservation {

	private final MeterRegistry meterRegistry;

	MicrometerTaskMaterializationObservation(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
	}

	@Override
	public void runCompleted(TaskMaterializationRunObservation observation) {
		Timer.builder("pocoma.task_materialization.run.duration")
				.description("Duration of one task materialization worker run.")
				.tag("worker_id", observation.workerId())
				.tag("segment_index", Integer.toString(observation.partition().segmentIndex()))
				.tag("segment_count", Integer.toString(observation.partition().segmentCount()))
				.register(meterRegistry)
				.record(observation.durationNanos(), TimeUnit.NANOSECONDS);
		Counter.builder("pocoma.task_materialization.candidates.selected.total")
				.description("Number of event pipeline candidates selected by the materializer.")
				.tag("worker_id", observation.workerId())
				.tag("segment_index", Integer.toString(observation.partition().segmentIndex()))
				.tag("segment_count", Integer.toString(observation.partition().segmentCount()))
				.register(meterRegistry)
				.increment(observation.selectedCandidateCount());
	}

	@Override
	public void materializationCompleted(
			TaskMaterializationEventObservation observation,
			MaterializationResult result) {
		EventPipelineMaterializationCandidate candidate = observation.candidate();
		String pipelineId = candidate.pipeline().pipelineId().value();
		String pipelineVersion = Integer.toString(candidate.pipeline().pipelineVersion());
		String eventType = candidate.event().eventType();
		String outcome = result.outcome().name();
		Timer.builder("pocoma.task_materialization.event.duration")
				.description("Duration of one event pipeline materialization attempt.")
				.tag("pipeline_id", pipelineId)
				.tag("pipeline_version", pipelineVersion)
				.tag("event_type", eventType)
				.tag("outcome", outcome)
				.register(meterRegistry)
				.record(observation.durationNanos(), TimeUnit.NANOSECONDS);
		Timer.builder("pocoma.task_materialization.event.lag")
				.description("Age of a business event when its pipeline materialization completes.")
				.tag("pipeline_id", pipelineId)
				.tag("pipeline_version", pipelineVersion)
				.tag("event_type", eventType)
				.tag("outcome", outcome)
				.register(meterRegistry)
				.record(nonNegative(Duration.between(candidate.event().createdAt(), observation.completedAt())));
		Counter.builder("pocoma.task_materialization.materializations.total")
				.description("Number of event pipeline materialization attempts by outcome.")
				.tag("pipeline_id", pipelineId)
				.tag("pipeline_version", pipelineVersion)
				.tag("event_type", eventType)
				.tag("outcome", outcome)
				.register(meterRegistry)
				.increment();
		if (result.taskCount() > 0) {
			Counter.builder("pocoma.task_materialization.tasks.created.total")
					.description("Number of durable pipeline tasks created by the materializer.")
					.tag("pipeline_id", pipelineId)
					.tag("pipeline_version", pipelineVersion)
					.register(meterRegistry)
					.increment(result.taskCount());
		}
	}

	private static Duration nonNegative(Duration duration) {
		return duration.isNegative() ? Duration.ZERO : duration;
	}
}
