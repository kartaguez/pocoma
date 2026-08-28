package com.kartaguez.pocoma.supra.worker.pipelinetask.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.engine.taskexecution.model.ConfiguredTaskExecutionBinding;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutorSettings;
import com.kartaguez.pocoma.supra.worker.pipelinetask.core.PipelineTaskExecutorWorkerSettings;

@ConfigurationProperties(prefix = "pocoma.pipeline.task-execution")
public class PipelineTaskExecutorWorkerProperties {

	private boolean enabled = false;
	private String workerId = "local";
	private int batchSize = 100;
	private Duration pollingInterval = Duration.ofMillis(100);
	private Duration leaseDuration = PipelineTaskExecutorSettings.DEFAULT_LEASE_DURATION;
	private Duration heartbeatInterval;
	private int threadCount = PipelineTaskExecutorSettings.DEFAULT_THREAD_COUNT;
	private int queueCapacity = PipelineTaskExecutorSettings.DEFAULT_QUEUE_CAPACITY;
	private int maxRetries = PipelineTaskExecutorSettings.DEFAULT_MAX_RETRIES;
	private Duration initialBackoff = PipelineTaskExecutorSettings.DEFAULT_INITIAL_BACKOFF;
	private Duration maxBackoff = PipelineTaskExecutorSettings.DEFAULT_MAX_BACKOFF;
	private int segmentIndex = 0;
	private int segmentCount = 1;
	private boolean wakeSignalsEnabled = true;
	private Duration capacityWakeupMinInterval = PipelineTaskExecutorSettings.DEFAULT_CAPACITY_WAKEUP_MIN_INTERVAL;
	private List<PipelineBindingProperties> pipelines = new ArrayList<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getWorkerId() {
		return workerId;
	}

	public void setWorkerId(String workerId) {
		this.workerId = workerId;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public Duration getPollingInterval() {
		return pollingInterval;
	}

	public void setPollingInterval(Duration pollingInterval) {
		this.pollingInterval = pollingInterval;
	}

	public Duration getLeaseDuration() {
		return leaseDuration;
	}

	public void setLeaseDuration(Duration leaseDuration) {
		this.leaseDuration = leaseDuration;
	}

	public Duration getHeartbeatInterval() {
		return heartbeatInterval;
	}

	public void setHeartbeatInterval(Duration heartbeatInterval) {
		this.heartbeatInterval = heartbeatInterval;
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

	public boolean isWakeSignalsEnabled() {
		return wakeSignalsEnabled;
	}

	public void setWakeSignalsEnabled(boolean wakeSignalsEnabled) {
		this.wakeSignalsEnabled = wakeSignalsEnabled;
	}

	public Duration getCapacityWakeupMinInterval() {
		return capacityWakeupMinInterval;
	}

	public void setCapacityWakeupMinInterval(Duration capacityWakeupMinInterval) {
		this.capacityWakeupMinInterval = capacityWakeupMinInterval;
	}

	public List<PipelineBindingProperties> getPipelines() {
		return pipelines;
	}

	public void setPipelines(List<PipelineBindingProperties> pipelines) {
		this.pipelines = pipelines == null ? new ArrayList<>() : new ArrayList<>(pipelines);
	}

	public PipelineTaskExecutorSettings toExecutorSettings() {
		return new PipelineTaskExecutorSettings(
				threadCount,
				queueCapacity,
				maxRetries,
				initialBackoff,
				maxBackoff,
				leaseDuration,
				heartbeatInterval(),
				capacityWakeupMinInterval);
	}

	public PipelineTaskExecutorWorkerSettings toWorkerSettings() {
		ProjectionPartition partition = new ProjectionPartition(segmentIndex, segmentCount);
		return new PipelineTaskExecutorWorkerSettings(
				enabled,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-pipeline-task-executor",
				batchSize,
				pollingInterval,
				leaseDuration,
				partition,
				wakeSignalsEnabled);
	}

	public List<ConfiguredTaskExecutionBinding> toBindings() {
		return pipelines.stream()
				.map(PipelineBindingProperties::toBinding)
				.toList();
	}

	private Duration heartbeatInterval() {
		if (heartbeatInterval != null) {
			return heartbeatInterval;
		}
		return PipelineTaskExecutorSettings.defaultHeartbeatInterval(leaseDuration);
	}

	public static final class PipelineBindingProperties {
		private String pipelineId;
		private int pipelineVersion = 1;
		private boolean enabled = true;
		private List<String> taskTypes = new ArrayList<>();

		public String getPipelineId() {
			return pipelineId;
		}

		public void setPipelineId(String pipelineId) {
			this.pipelineId = pipelineId;
		}

		public int getPipelineVersion() {
			return pipelineVersion;
		}

		public void setPipelineVersion(int pipelineVersion) {
			this.pipelineVersion = pipelineVersion;
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public List<String> getTaskTypes() {
			return taskTypes;
		}

		public void setTaskTypes(List<String> taskTypes) {
			this.taskTypes = taskTypes == null ? new ArrayList<>() : new ArrayList<>(taskTypes);
		}

		private ConfiguredTaskExecutionBinding toBinding() {
			List<String> configuredTaskTypes = taskTypes.isEmpty() ? List.of("*") : taskTypes;
			return new ConfiguredTaskExecutionBinding(
					new PipelineDefinition(PipelineId.of(pipelineId), pipelineVersion),
					configuredTaskTypes,
					enabled);
		}
	}
}
