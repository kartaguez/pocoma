package com.kartaguez.pocoma.engine.consumption.v3.port;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.ClaimedSlot;
import com.kartaguez.pocoma.engine.consumption.v3.LifecycleSettings.RetryPolicy;
import com.kartaguez.pocoma.engine.consumption.v3.PartitionLease;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.time.*;
import java.util.*;

public interface ConsumptionSlotStore {
    enum Protection {
        VALID,
        ABSENT,
        STALE_OR_EXPIRED
    }

    enum SlotObservation {
        ABSENT,
        PRESENT
    }

    record ClaimCandidate(UUID consumableId, UUID freshToken) {}

    record GrantedClaim(ClaimedSlot claim, Instant claimedUntil) {}

    record Cession(int attemptCount, String state, Instant retryAfter) {}

    enum Inspection {
        LOCKED,
        PRESENT_UNLOCKED,
        ABSENT
    }

    record ClaimInspection(ClaimedSlot claim, Inspection inspection) {}

    List<GrantedClaim> acquire(
            PartitionLease protectedLease, List<ClaimCandidate> candidates, Duration claimDuration);

    List<GrantedClaim> renew(List<ClaimedSlot> claims, Duration claimDuration);

    Optional<Cession> cede(ClaimedSlot claim, RetryPolicy policy);

    List<ClaimInspection> inspectResolved(List<ClaimedSlot> claims);

    Protection protectFinal(ClaimedSlot claim);

    void deleteProtected(ClaimedSlot claim);

    SlotObservation observe(ConsumptionId id, UUID consumableId, RecoveryReadContext context);
}
