package com.kartaguez.pocoma.contract.consumption.spi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings.RetryPolicy;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

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
