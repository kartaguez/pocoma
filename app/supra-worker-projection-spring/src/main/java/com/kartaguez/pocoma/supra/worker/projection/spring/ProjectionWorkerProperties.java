package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder.ProjectionTaskBuilderSettings;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorSettings;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.ProjectionTaskExecutorWorkerSettings;

@ConfigurationProperties(prefix = "pocoma.projection.worker")
public class ProjectionWorkerProperties {

	private boolean enabled = true;

	private int threadCount = ProjectionTaskExecutorSettings.DEFAULT_THREAD_COUNT;

	private int queueCapacity = ProjectionTaskExecutorSettings.DEFAULT_QUEUE_CAPACITY;

	private int maxRetries = ProjectionTaskExecutorSettings.DEFAULT_MAX_RETRIES;

	private Duration initialBackoff = ProjectionTaskExecutorSettings.DEFAULT_INITIAL_BACKOFF;

	private Duration maxBackoff = ProjectionTaskExecutorSettings.DEFAULT_MAX_BACKOFF;

	private boolean eventListenerEnabled = false;

	private boolean wakeSignalsEnabled = true;

	private boolean taskBuilderEnabled = true;

	private boolean taskExecutorEnabled = true;

	private String workerId = "local";

	private int segmentIndex = 0;

	private int segmentCount = 1;

	private int taskBuilderBatchSize = 100;

	private int taskExecutorBatchSize = 100;

	private Duration taskBuilderPollingInterval = Duration.ofMillis(250);

	private Duration taskExecutorPollingInterval = Duration.ofMillis(100);

	private Duration taskBuilderLeaseDuration = Duration.ofSeconds(30);

	private Duration taskExecutorLeaseDuration = Duration.ofSeconds(30);

	private Duration capacityWakeupMinInterval = ProjectionTaskExecutorSettings.DEFAULT_CAPACITY_WAKEUP_MIN_INTERVAL;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

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

	public boolean isEventListenerEnabled() {
		return eventListenerEnabled;
	}

	public void setEventListenerEnabled(boolean eventListenerEnabled) {
		this.eventListenerEnabled = eventListenerEnabled;
	}

	public boolean isWakeSignalsEnabled() {
		return wakeSignalsEnabled;
	}

	public void setWakeSignalsEnabled(boolean wakeSignalsEnabled) {
		this.wakeSignalsEnabled = wakeSignalsEnabled;
	}

	public boolean isTaskBuilderEnabled() {
		return taskBuilderEnabled;
	}

	public void setTaskBuilderEnabled(boolean taskBuilderEnabled) {
		this.taskBuilderEnabled = taskBuilderEnabled;
	}

	public boolean isTaskExecutorEnabled() {
		return taskExecutorEnabled;
	}

	public void setTaskExecutorEnabled(boolean taskExecutorEnabled) {
		this.taskExecutorEnabled = taskExecutorEnabled;
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

	public int getTaskExecutorBatchSize() {
		return taskExecutorBatchSize;
	}

	public void setTaskExecutorBatchSize(int taskExecutorBatchSize) {
		this.taskExecutorBatchSize = taskExecutorBatchSize;
	}

	public Duration getTaskBuilderPollingInterval() {
		return taskBuilderPollingInterval;
	}

	public void setTaskBuilderPollingInterval(Duration taskBuilderPollingInterval) {
		this.taskBuilderPollingInterval = taskBuilderPollingInterval;
	}

	public Duration getTaskExecutorPollingInterval() {
		return taskExecutorPollingInterval;
	}

	public void setTaskExecutorPollingInterval(Duration taskExecutorPollingInterval) {
		this.taskExecutorPollingInterval = taskExecutorPollingInterval;
	}

	public Duration getTaskBuilderLeaseDuration() {
		return taskBuilderLeaseDuration;
	}

	public void setTaskBuilderLeaseDuration(Duration taskBuilderLeaseDuration) {
		this.taskBuilderLeaseDuration = taskBuilderLeaseDuration;
	}

	public Duration getTaskExecutorLeaseDuration() {
		return taskExecutorLeaseDuration;
	}

	public void setTaskExecutorLeaseDuration(Duration taskExecutorLeaseDuration) {
		this.taskExecutorLeaseDuration = taskExecutorLeaseDuration;
	}

	public Duration getCapacityWakeupMinInterval() {
		return capacityWakeupMinInterval;
	}

	public void setCapacityWakeupMinInterval(Duration capacityWakeupMinInterval) {
		this.capacityWakeupMinInterval = capacityWakeupMinInterval;
	}

	ProjectionTaskExecutorSettings toSettings() {
		return new ProjectionTaskExecutorSettings(
				threadCount,
				queueCapacity,
				maxRetries,
				initialBackoff,
				maxBackoff,
				capacityWakeupMinInterval);
	}

	ProjectionTaskBuilderSettings toTaskBuilderSettings() {
		ProjectionPartition partition = partition();
		return new ProjectionTaskBuilderSettings(
				taskBuilderEnabled,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-task-builder",
				taskBuilderBatchSize,
				taskBuilderPollingInterval,
				taskBuilderLeaseDuration,
				partition,
				wakeSignalsEnabled);
	}

	ProjectionTaskExecutorWorkerSettings toTaskExecutorSettings() {
		ProjectionPartition partition = partition();
		return new ProjectionTaskExecutorWorkerSettings(
				taskExecutorEnabled,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-task-executor",
				taskExecutorBatchSize,
				taskExecutorPollingInterval,
				taskExecutorLeaseDuration,
				partition,
				wakeSignalsEnabled);
	}

	private ProjectionPartition partition() {
		return new ProjectionPartition(segmentIndex, segmentCount);
	}
}
