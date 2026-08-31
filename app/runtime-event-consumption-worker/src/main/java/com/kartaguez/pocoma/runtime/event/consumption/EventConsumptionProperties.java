package com.kartaguez.pocoma.runtime.event.consumption;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.event-consumption")
public class EventConsumptionProperties {
	private boolean enabled;
	private String pipelineId = "unconfigured";
	private int pipelineVersion = 1;
	private int segmentIndex;
	private int segmentCount = 1;
	private String workerId = "event-consumption-worker";
	private Duration claimLease = Duration.ofSeconds(30);
	private int maxCandidatesInspected = 100;
	private int maxConsumptionsExecuted = 10;
	private Duration pollInterval = Duration.ofSeconds(1);
	private Duration runtimeFailureBackoff = Duration.ofSeconds(5);

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getPipelineId() { return pipelineId; }
	public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }
	public int getPipelineVersion() { return pipelineVersion; }
	public void setPipelineVersion(int pipelineVersion) { this.pipelineVersion = pipelineVersion; }
	public int getSegmentIndex() { return segmentIndex; }
	public void setSegmentIndex(int segmentIndex) { this.segmentIndex = segmentIndex; }
	public int getSegmentCount() { return segmentCount; }
	public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
	public String getWorkerId() { return workerId; }
	public void setWorkerId(String workerId) { this.workerId = workerId; }
	public Duration getClaimLease() { return claimLease; }
	public void setClaimLease(Duration claimLease) { this.claimLease = claimLease; }
	public int getMaxCandidatesInspected() { return maxCandidatesInspected; }
	public void setMaxCandidatesInspected(int value) { this.maxCandidatesInspected = value; }
	public int getMaxConsumptionsExecuted() { return maxConsumptionsExecuted; }
	public void setMaxConsumptionsExecuted(int value) { this.maxConsumptionsExecuted = value; }
	public Duration getPollInterval() { return pollInterval; }
	public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
	public Duration getRuntimeFailureBackoff() { return runtimeFailureBackoff; }
	public void setRuntimeFailureBackoff(Duration value) { this.runtimeFailureBackoff = value; }
}
