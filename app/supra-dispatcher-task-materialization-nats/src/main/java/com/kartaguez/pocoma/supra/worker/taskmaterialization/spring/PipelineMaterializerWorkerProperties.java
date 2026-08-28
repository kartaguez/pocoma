package com.kartaguez.pocoma.supra.worker.taskmaterialization.spring;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.engine.taskmaterialization.model.ConfiguredPipelineBinding;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.supra.worker.taskmaterialization.core.EventToPipelineTaskMaterializerSettings;

@ConfigurationProperties(prefix = "pocoma.pipeline.materialization")
public class PipelineMaterializerWorkerProperties {

	private boolean enabled = false;
	private boolean wakeSignalsEnabled = true;
	private String workerId = "local";
	private int batchSize = 100;
	private Duration pollingInterval = Duration.ofMillis(250);
	private Duration safetyDelay = Duration.ZERO;
	private int segmentIndex = 0;
	private int segmentCount = 1;
	private List<PipelineBindingProperties> pipelines = new ArrayList<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
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

	public Duration getSafetyDelay() {
		return safetyDelay;
	}

	public void setSafetyDelay(Duration safetyDelay) {
		this.safetyDelay = safetyDelay;
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

	public List<PipelineBindingProperties> getPipelines() {
		return pipelines;
	}

	public void setPipelines(List<PipelineBindingProperties> pipelines) {
		this.pipelines = pipelines == null ? new ArrayList<>() : new ArrayList<>(pipelines);
	}

	EventToPipelineTaskMaterializerSettings toSettings() {
		ProjectionPartition partition = new ProjectionPartition(segmentIndex, segmentCount);
		return new EventToPipelineTaskMaterializerSettings(
				enabled,
				workerId + "-s" + partition.segmentIndex() + "-of-" + partition.segmentCount() + "-pipeline-materializer",
				batchSize,
				pollingInterval,
				safetyDelay,
				partition,
				wakeSignalsEnabled);
	}

	List<ConfiguredPipelineBinding> toBindings() {
		return pipelines.stream()
				.map(PipelineBindingProperties::toBinding)
				.toList();
	}

	public static class PipelineBindingProperties {
		private String pipelineId;
		private int pipelineVersion;
		private boolean enabled = true;
		private List<String> eventTypes = new ArrayList<>();
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

		public List<String> getEventTypes() {
			return eventTypes;
		}

		public void setEventTypes(List<String> eventTypes) {
			this.eventTypes = eventTypes == null ? new ArrayList<>() : new ArrayList<>(eventTypes);
		}

		public List<String> getTaskTypes() {
			return taskTypes;
		}

		public void setTaskTypes(List<String> taskTypes) {
			this.taskTypes = taskTypes == null ? new ArrayList<>() : new ArrayList<>(taskTypes);
		}

		private ConfiguredPipelineBinding toBinding() {
			return new ConfiguredPipelineBinding(
					new PipelineDefinition(PipelineId.of(pipelineId), pipelineVersion),
					eventTypes,
					enabled);
		}
	}
}
