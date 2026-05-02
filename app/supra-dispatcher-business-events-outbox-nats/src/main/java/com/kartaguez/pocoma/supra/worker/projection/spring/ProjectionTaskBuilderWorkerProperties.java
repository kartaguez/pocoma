package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder.ProjectionTaskBuilderSettings;

@ConfigurationProperties(prefix = "pocoma.projection.worker")
public class ProjectionTaskBuilderWorkerProperties {

	private boolean wakeSignalsEnabled = true;
	private String workerId = "local";
	private int segmentIndex = 0;
	private int segmentCount = 1;
	private int taskBuilderBatchSize = 100;
	private Duration taskBuilderPollingInterval = Duration.ofMillis(250);
	private Duration taskBuilderLeaseDuration = Duration.ofSeconds(30);
	private int taskBuilderThreadCount = ProjectionTaskBuilderSettings.DEFAULT_THREAD_COUNT;
	private int taskBuilderQueueCapacity = ProjectionTaskBuilderSettings.DEFAULT_QUEUE_CAPACITY;
	private int taskBuilderMaxRetries = ProjectionTaskBuilderSettings.DEFAULT_MAX_RETRIES;
	private Duration taskBuilderInitialBackoff = ProjectionTaskBuilderSettings.DEFAULT_INITIAL_BACKOFF;
	private Duration taskBuilderMaxBackoff = ProjectionTaskBuilderSettings.DEFAULT_MAX_BACKOFF;

	public boolean isWakeSignalsEnabled() {
		return wakeSignalsEnabled;
	}

	public void setWakeSignalsEnabled(boolean wakeSignalsEnabled) {
		this.wakeSignalsEnabled = wakeSignalsEnabled;
	}

	public String getWorkerId() {
		return workerId;
	}

	public void setWorkerId(String workerId) {
		this.workerId = workerId;
	}

	public int getSegmentIndex() {
		return segmentIndex;
	}

	public void setSegmentIndex(int segmentIndex) {
		this.segmentIndex = segmentIndex;
	}

	public int getSegmentCount() {
		return segmentCount;
	}

	public void setSegmentCount(int segmentCount) {
		this.segmentCount = segmentCount;
	}

	public int getTaskBuilderBatchSize() {
		return taskBuilderBatchSize;
	}

	public void setTaskBuilderBatchSize(int taskBuilderBatchSize) {
		this.taskBuilderBatchSize = taskBuilderBatchSize;
	}

	public Duration getTaskBuilderPollingInterval() {
		return taskBuilderPollingInterval;
	}

	public void setTaskBuilderPollingInterval(Duration taskBuilderPollingInterval) {
		this.taskBuilderPollingInterval = taskBuilderPollingInterval;
	}

	public Duration getTaskBuilderLeaseDuration() {
		return taskBuilderLeaseDuration;
	}

	public void setTaskBuilderLeaseDuration(Duration taskBuilderLeaseDuration) {
		this.taskBuilderLeaseDuration = taskBuilderLeaseDuration;
	}

	public int getTaskBuilderThreadCount() {
		return taskBuilderThreadCount;
	}

	public void setTaskBuilderThreadCount(int taskBuilderThreadCount) {
		this.taskBuilderThreadCount = taskBuilderThreadCount;
	}

	public int getTaskBuilderQueueCapacity() {
		return taskBuilderQueueCapacity;
	}

	public void setTaskBuilderQueueCapacity(int taskBuilderQueueCapacity) {
		this.taskBuilderQueueCapacity = taskBuilderQueueCapacity;
	}

	public int getTaskBuilderMaxRetries() {
		return taskBuilderMaxRetries;
	}

	public void setTaskBuilderMaxRetries(int taskBuilderMaxRetries) {
		this.taskBuilderMaxRetries = taskBuilderMaxRetries;
	}

	public Duration getTaskBuilderInitialBackoff() {
		return taskBuilderInitialBackoff;
	}

	public void setTaskBuilderInitialBackoff(Duration taskBuilderInitialBackoff) {
		this.taskBuilderInitialBackoff = taskBuilderInitialBackoff;
	}

	public Duration getTaskBuilderMaxBackoff() {
		return taskBuilderMaxBackoff;
	}

	public void setTaskBuilderMaxBackoff(Duration taskBuilderMaxBackoff) {
		this.taskBuilderMaxBackoff = taskBuilderMaxBackoff;
	}

	ProjectionTaskBuilderSettings toTaskBuilderSettings() {
		ProjectionPartition partition = new ProjectionPartition(segmentIndex, segmentCount);
		return new ProjectionTaskBuilderSettings(
				true,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-task-builder",
				taskBuilderBatchSize,
				taskBuilderPollingInterval,
				taskBuilderLeaseDuration,
				partition,
				wakeSignalsEnabled,
				taskBuilderThreadCount,
				taskBuilderQueueCapacity,
				taskBuilderMaxRetries,
				taskBuilderInitialBackoff,
				taskBuilderMaxBackoff);
	}
}
