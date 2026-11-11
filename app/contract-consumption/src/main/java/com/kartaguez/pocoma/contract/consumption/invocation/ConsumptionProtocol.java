package com.kartaguez.pocoma.contract.consumption.invocation;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.Cession;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Resolution;
import java.util.*;

/** Issuer-bound invocation surface. IDs are diagnostics, never execution permissions. */
public interface ConsumptionProtocol {
    public enum Status {
        COMMITTED,
        CLAIM_LOST,
        ROLLED_BACK,
        RECOVERY_PENDING,
        INCONSISTENT
    }
    public record Attempt(
            Status status,
            Resolution resolution,
            RecoveryObservation observation,
            Throwable cause,
            FailureKind failureKind,
            Cession cession,
            Resolution cessionResolution) {
        public Attempt(
                Status status,
                Resolution resolution,
                RecoveryObservation observation,
                Throwable cause) {
            this(status, resolution, observation, cause, FailureKind.NON_COUNTABLE, null, null);
        }
    }
    interface Handle {
        ConsumptionId consumptionId();
        UUID consumableId();
        int partitionId();
    }
    interface Partition {
        ConsumptionId consumptionId();
        int partitionId();
        boolean sameIncarnation(Partition other);
    }
    /** L2 publishes the local continuation; polling only remembers it. */
    record Navigation(List<UUID> keys, ConsumptionNavigation.Walk continuation, boolean tourComplete) {
        public Navigation {keys=List.copyOf(keys);Objects.requireNonNull(continuation);}
    }
    record Acquisition(List<Handle> claims, int examinedCount,
            Optional<ConsumptionNavigation.Walk> confirmedContinuation, boolean suspendPartition) {
        public Acquisition { claims=List.copyOf(claims);Objects.requireNonNull(confirmedContinuation); }
    }
    Optional<Partition> acquirePartition(int partition);
    List<Partition> renewPartitions(List<Partition> partitions);
    void renewClaim(Handle handle);
    Navigation navigate(Partition partition, ConsumptionNavigation.Walk walk, int limit);
    Acquisition acquire(Partition partition, List<UUID> keys, ConsumptionNavigation.Walk continuation);
    Acquisition acquire(Partition partition, List<UUID> keys);
    Attempt execute(Handle handle);
    void abandon(Handle handle);
    boolean retainsCapacity(Handle handle);
    int settlePending();
    boolean hasUnresolvedRecovery();
    DiscoveryReports.Progress discover(Partition partition);
}
