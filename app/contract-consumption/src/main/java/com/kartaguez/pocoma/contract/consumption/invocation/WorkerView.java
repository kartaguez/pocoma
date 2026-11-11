package com.kartaguez.pocoma.contract.consumption.invocation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import java.util.*;

public interface WorkerView {
    public enum State {
        STARTING,
        RUNNING,
        DRAINING,
        STOPPED
    }
    public record Snapshot(
            State state,
            int handles,
            int started,
            int localExited,
            int operations,
            int submissions,
            int cleanups,
            int renewalCalls,
            int unknownRenewals,
            boolean deadlineExceeded) {}
    public record RenewalSnapshot(
            ConsumptionDefinition.Authority authority, int lane, boolean unknown) {}
    Snapshot snapshot();
    List<RenewalSnapshot> renewalSnapshot();
    Map<ConsumptionDefinition.Authority, Long> localLeases();
    boolean isRunning();
    void start();
    void requestDrain();
    void requestDrain(Runnable stopped);
    void signal();
}
