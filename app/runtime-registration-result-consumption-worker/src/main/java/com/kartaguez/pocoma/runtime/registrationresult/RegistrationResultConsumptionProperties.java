package com.kartaguez.pocoma.runtime.registrationresult;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pocoma.registration-result-consumption")
public class RegistrationResultConsumptionProperties {
    private boolean enabled;
    private int segmentIndex, segmentCount = 1, maxCandidatesInspected = 100, maxConsumptionsExecuted = 10;
    private String workerId = "registration-result-consumption-worker";
    private Duration claimLease = Duration.ofSeconds(30), pollInterval = Duration.ofSeconds(1), runtimeFailureBackoff = Duration.ofSeconds(5);
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getSegmentIndex(){return segmentIndex;} public void setSegmentIndex(int v){segmentIndex=v;}
    public int getSegmentCount(){return segmentCount;} public void setSegmentCount(int v){segmentCount=v;}
    public int getMaxCandidatesInspected(){return maxCandidatesInspected;} public void setMaxCandidatesInspected(int v){maxCandidatesInspected=v;}
    public int getMaxConsumptionsExecuted(){return maxConsumptionsExecuted;} public void setMaxConsumptionsExecuted(int v){maxConsumptionsExecuted=v;}
    public String getWorkerId(){return workerId;} public void setWorkerId(String v){workerId=v;}
    public Duration getClaimLease(){return claimLease;} public void setClaimLease(Duration v){claimLease=v;}
    public Duration getPollInterval(){return pollInterval;} public void setPollInterval(Duration v){pollInterval=v;}
    public Duration getRuntimeFailureBackoff(){return runtimeFailureBackoff;} public void setRuntimeFailureBackoff(Duration v){runtimeFailureBackoff=v;}
}
