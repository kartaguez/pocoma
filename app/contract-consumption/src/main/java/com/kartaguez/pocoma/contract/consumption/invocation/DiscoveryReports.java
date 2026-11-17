package com.kartaguez.pocoma.contract.consumption.invocation;

import java.util.OptionalLong;

public interface DiscoveryReports {
    public enum Status {
        COMMITTED,
        BUSY_OR_NOT_OWNER,
        STALE_CHECKPOINT,
        CAPACITY
    }
    public record EventProgress(
            Status status, OptionalLong checkpoint, int examined, int inserted) {}
    public record RequestProgress(
            Status status,
            OptionalLong checkpoint,
            int examined,
            int advanced,
            int committedAdmissions) {}
    record Progress(Status status, OptionalLong checkpoint, int examined, int advanced, int committedAdmissions) {}
}
