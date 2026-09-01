package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.task-consumption")
public class TaskConsumptionProperties {
	private boolean enabled;
	private String pipelineId = "unconfigured";
	private int pipelineVersion = 1;
	private List<String> taskTypes = List.of("COMPUTE_BALANCES_FOR_VERSION");
	private int segmentIndex;
	private int segmentCount = 1;
	private String workerId = "task-consumption-worker";
	private Duration claimLease = Duration.ofSeconds(30);
	private int maxCandidatesInspected = 100;
	private int maxConsumptionsExecuted = 10;
	private Duration pollInterval = Duration.ofSeconds(1);
	private Duration runtimeFailureBackoff = Duration.ofSeconds(5);
	public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
	public String getPipelineId(){return pipelineId;} public void setPipelineId(String value){pipelineId=value;}
	public int getPipelineVersion(){return pipelineVersion;} public void setPipelineVersion(int value){pipelineVersion=value;}
	public List<String> getTaskTypes(){return taskTypes;} public void setTaskTypes(List<String> value){taskTypes=List.copyOf(value);}
	public int getSegmentIndex(){return segmentIndex;} public void setSegmentIndex(int value){segmentIndex=value;}
	public int getSegmentCount(){return segmentCount;} public void setSegmentCount(int value){segmentCount=value;}
	public String getWorkerId(){return workerId;} public void setWorkerId(String value){workerId=value;}
	public Duration getClaimLease(){return claimLease;} public void setClaimLease(Duration value){claimLease=value;}
	public int getMaxCandidatesInspected(){return maxCandidatesInspected;} public void setMaxCandidatesInspected(int value){maxCandidatesInspected=value;}
	public int getMaxConsumptionsExecuted(){return maxConsumptionsExecuted;} public void setMaxConsumptionsExecuted(int value){maxConsumptionsExecuted=value;}
	public Duration getPollInterval(){return pollInterval;} public void setPollInterval(Duration value){pollInterval=value;}
	public Duration getRuntimeFailureBackoff(){return runtimeFailureBackoff;} public void setRuntimeFailureBackoff(Duration value){runtimeFailureBackoff=value;}
}
