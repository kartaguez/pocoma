package com.kartaguez.pocoma.runtime.command.consumption;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.command-consumption")
public class CommandConsumptionProperties {
	private boolean enabled;
	private String workerId;
	private Duration claimLease = Duration.ofSeconds(30);
	private int maxCandidatesInspected = 100;
	private int maxConsumptionsExecuted = 10;
	private Duration pollInterval = Duration.ofSeconds(1);
	private Duration runtimeFailureBackoff = Duration.ofSeconds(5);

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
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
