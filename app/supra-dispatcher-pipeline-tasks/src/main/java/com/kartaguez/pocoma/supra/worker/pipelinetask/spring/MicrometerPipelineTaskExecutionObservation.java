package com.kartaguez.pocoma.supra.worker.pipelinetask.spring;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.kartaguez.pocoma.engine.taskexecution.model.LegacyPipelineTask;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutionObservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

final class MicrometerPipelineTaskExecutionObservation implements PipelineTaskExecutionObservation {

	private final MeterRegistry meterRegistry;
	private final String workerId;
	private final String segmentIndex;
	private final String segmentCount;

	MicrometerPipelineTaskExecutionObservation(
			MeterRegistry meterRegistry,
			PipelineTaskExecutorWorkerProperties properties) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
		Objects.requireNonNull(properties, "properties must not be null");
		this.workerId = properties.getWorkerId();
		this.segmentIndex = Integer.toString(properties.getSegmentIndex());
		this.segmentCount = Integer.toString(properties.getSegmentCount());
	}

	@Override
	public void taskSubmitted(LegacyPipelineTask task) {
		Counter.builder("pocoma.pipeline_task_execution.submitted.total")
				.description("Number of claimed pipeline tasks submitted to local execution segments.")
				.tags(tags(task, "submitted"))
				.register(meterRegistry)
				.increment();
	}

	@Override
	public void taskSucceeded(LegacyPipelineTask task, long durationNanos) {
		recordExecution(task, "succeeded", durationNanos);
	}

	@Override
	public void taskFailed(LegacyPipelineTask task, long durationNanos) {
		recordExecution(task, "failed", durationNanos);
	}

	@Override
	public void tasksClaimed(int count) {
		Counter.builder("pocoma.pipeline_task_execution.claimed.total")
				.description("Number of pipeline tasks claimed and accepted by the executor dispatcher.")
				.tags(workerTags())
				.register(meterRegistry)
				.increment(count);
	}

	private void recordExecution(LegacyPipelineTask task, String outcome, long durationNanos) {
		Timer.builder("pocoma.pipeline_task_execution.execution.duration")
				.description("Duration of pipeline task execution.")
				.tags(tags(task, outcome))
				.register(meterRegistry)
				.record(durationNanos, TimeUnit.NANOSECONDS);
		Counter.builder("pocoma.pipeline_task_execution.execution.total")
				.description("Number of pipeline task executions by outcome.")
				.tags(tags(task, outcome))
				.register(meterRegistry)
				.increment();
	}

	private String[] tags(LegacyPipelineTask task, String outcome) {
		return new String[] {
				"worker_id", workerId,
				"segment_index", segmentIndex,
				"segment_count", segmentCount,
				"pipeline_id", task.pipeline().pipelineId().value(),
				"pipeline_version", Integer.toString(task.pipeline().pipelineVersion()),
				"task_type", task.taskType(),
				"outcome", outcome
		};
	}

	private String[] workerTags() {
		return new String[] {
				"worker_id", workerId,
				"segment_index", segmentIndex,
				"segment_count", segmentCount
		};
	}
}
