package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.projection-task-consumption")
public class CanonicalProjectionTaskProperties {
	private boolean enabled;
	private List<String> catalogProjectionTypes = new ArrayList<>(List.of("POT_BALANCES"));
	private List<String> locatorProjectionTypes = new ArrayList<>(List.of("POT_BALANCES"));
	private int segmentIndex;
	private int segmentCount = 1;
	private String workerId = "canonical-projection-task-worker";
	private Duration claimLease = Duration.ofSeconds(30);
	private Duration retryDelay = Duration.ofSeconds(30);
	private int maxCandidatesInspected = 100;
	private int maxConsumptionsExecuted = 10;
	private Duration pollInterval = Duration.ofSeconds(1);
	private Duration runtimeFailureBackoff = Duration.ofSeconds(5);
	public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
	public List<String> getCatalogProjectionTypes(){return catalogProjectionTypes;}
	public void setCatalogProjectionTypes(List<String> value){catalogProjectionTypes=value;}
	public List<String> getLocatorProjectionTypes(){return locatorProjectionTypes;}
	public void setLocatorProjectionTypes(List<String> value){locatorProjectionTypes=value;}
	public int getSegmentIndex(){return segmentIndex;} public void setSegmentIndex(int value){segmentIndex=value;}
	public int getSegmentCount(){return segmentCount;} public void setSegmentCount(int value){segmentCount=value;}
	public String getWorkerId(){return workerId;} public void setWorkerId(String value){workerId=value;}
	public Duration getClaimLease(){return claimLease;} public void setClaimLease(Duration value){claimLease=value;}
	public Duration getRetryDelay(){return retryDelay;} public void setRetryDelay(Duration value){retryDelay=value;}
	public int getMaxCandidatesInspected(){return maxCandidatesInspected;} public void setMaxCandidatesInspected(int value){maxCandidatesInspected=value;}
	public int getMaxConsumptionsExecuted(){return maxConsumptionsExecuted;} public void setMaxConsumptionsExecuted(int value){maxConsumptionsExecuted=value;}
	public Duration getPollInterval(){return pollInterval;} public void setPollInterval(Duration value){pollInterval=value;}
	public Duration getRuntimeFailureBackoff(){return runtimeFailureBackoff;} public void setRuntimeFailureBackoff(Duration value){runtimeFailureBackoff=value;}
}
