package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorSettings;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorWorkerSettings;

@ConfigurationProperties(prefix = "pocoma.projection.worker")
public class ProjectionTaskExecutorWorkerProperties {

	private int threadCount = ProjectionTaskExecutorSettings.DEFAULT_THREAD_COUNT;
	private int queueCapacity = ProjectionTaskExecutorSettings.DEFAULT_QUEUE_CAPACITY;
	private int maxRetries = ProjectionTaskExecutorSettings.DEFAULT_MAX_RETRIES;
	private Duration initialBackoff = ProjectionTaskExecutorSettings.DEFAULT_INITIAL_BACKOFF;
	private Duration maxBackoff = ProjectionTaskExecutorSettings.DEFAULT_MAX_BACKOFF;
	private boolean wakeSignalsEnabled = true;
	private String workerId = "local";
	private int segmentIndex = 0;
	private int segmentCount = 1;
	private int taskExecutorBatchSize = 100;
	private Duration taskExecutorPollingInterval = Duration.ofMillis(100);
	private Duration taskExecutorLeaseDuration = Duration.ofSeconds(30);

	public int getThreadCount() {
		return threadCount;
	}

	public void setThreadCount(int threadCount) {
		this.threadCount = threadCount;
	}

	public int getQueueCapacity() {
		return queueCapacity;
	}

	public void setQueueCapacity(int queueCapacity) {
		this.queueCapacity = queueCapacity;
	}

	public int getMaxRetries() {
		return maxRetries;
	}

	public void setMaxRetries(int maxRetries) {
		this.maxRetries = maxRetries;
	}

	public Duration getInitialBackoff() {
		return initialBackoff;
	}

	public void setInitialBackoff(Duration initialBackoff) {
		this.initialBackoff = initialBackoff;
	}

	public Duration getMaxBackoff() {
		return maxBackoff;
	}

	public void setMaxBackoff(Duration maxBackoff) {
		this.maxBackoff = maxBackoff;
	}

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

	public int getTaskExecutorBatchSize() {
		return taskExecutorBatchSize;
	}

	public void setTaskExecutorBatchSize(int taskExecutorBatchSize) {
		this.taskExecutorBatchSize = taskExecutorBatchSize;
	}

	public Duration getTaskExecutorPollingInterval() {
		return taskExecutorPollingInterval;
	}

	public void setTaskExecutorPollingInterval(Duration taskExecutorPollingInterval) {
		this.taskExecutorPollingInterval = taskExecutorPollingInterval;
	}

	public Duration getTaskExecutorLeaseDuration() {
		return taskExecutorLeaseDuration;
	}

	public void setTaskExecutorLeaseDuration(Duration taskExecutorLeaseDuration) {
		this.taskExecutorLeaseDuration = taskExecutorLeaseDuration;
	}

	ProjectionTaskExecutorSettings toSettings() {
		return new ProjectionTaskExecutorSettings(threadCount, queueCapacity, maxRetries, initialBackoff, maxBackoff);
	}

	ProjectionTaskExecutorWorkerSettings toTaskExecutorSettings() {
		ProjectionPartition partition = new ProjectionPartition(segmentIndex, segmentCount);
		return new ProjectionTaskExecutorWorkerSettings(
				true,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-task-executor",
				taskExecutorBatchSize,
				taskExecutorPollingInterval,
				taskExecutorLeaseDuration,
				partition,
				wakeSignalsEnabled);
	}
}
