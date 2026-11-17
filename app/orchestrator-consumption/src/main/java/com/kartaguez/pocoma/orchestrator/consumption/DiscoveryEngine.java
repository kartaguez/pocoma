package com.kartaguez.pocoma.orchestrator.consumption;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports.EventProgress;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports.RequestProgress;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports.Status;
import com.kartaguez.pocoma.contract.consumption.observation.DiscoveryListener;
import com.kartaguez.pocoma.contract.consumption.spi.BurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;

/** Bounded Discovery only. Durable slots coordinate it with the independent lifecycle Engine. */
public final class DiscoveryEngine {
    ConsumptionDefinition definition() { return definition; }










    private final ConsumptionDefinition definition;
    private final AttemptTransactions effects;
    private final PartitionOwnershipStore ownership;
    private final DiscoveryAdmissionStore admissions;
    private final DiscoverySettings settings;
    private final AttemptTransactions sourceReads;
    private final NonBurnableReferences events;
    private final BurnableReferences requests;
    private final Predicate<NonBurnableReferences.Reference> relevant;
    private final DiscoveryListener listener;

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            DiscoverySettings settings,
            AttemptTransactions sourceReads,
            NonBurnableReferences source,
            Predicate<NonBurnableReferences.Reference> relevant) {
        this(
                definition,
                effects,
                ownership,
                admissions,
                settings,
                sourceReads,
                source,
                relevant,
                DiscoveryListener.NOOP);
    }

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            DiscoverySettings settings,
            AttemptTransactions sourceReads,
            NonBurnableReferences source,
            Predicate<NonBurnableReferences.Reference> relevant,
            DiscoveryListener listener) {
        this.definition = Objects.requireNonNull(definition);
        this.effects = Objects.requireNonNull(effects);
        this.ownership = Objects.requireNonNull(ownership);
        this.admissions = Objects.requireNonNull(admissions);
        this.settings = Objects.requireNonNull(settings);
        this.sourceReads = Objects.requireNonNull(sourceReads);
        this.events = Objects.requireNonNull(source);
        this.relevant = Objects.requireNonNull(relevant);
        this.requests = null;
        this.listener = Objects.requireNonNull(listener);
        if (burnable(definition.family()) || definition.family() != source.family())
            throw new IllegalArgumentException("incompatible event source");
    }

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            DiscoverySettings settings,
            BurnableReferences source) {
        this(definition, effects, ownership, admissions, settings, source, DiscoveryListener.NOOP);
    }

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            DiscoverySettings settings,
            BurnableReferences source,
            DiscoveryListener listener) {
        this.definition = Objects.requireNonNull(definition);
        this.effects = Objects.requireNonNull(effects);
        this.ownership = Objects.requireNonNull(ownership);
        this.admissions = Objects.requireNonNull(admissions);
        this.settings = Objects.requireNonNull(settings);
        this.sourceReads = effects;
        this.requests = Objects.requireNonNull(source);
        this.events = null;
        this.relevant = null;
        this.listener = Objects.requireNonNull(listener);
        if (!burnable(definition.family()) || definition.family() != source.family())
            throw new IllegalArgumentException("incompatible request source");
    }

    private static boolean burnable(ConsumableFamily family) {
        return family == ConsumableFamily.COMMAND || family == ConsumableFamily.IDENTITY_REQUEST;
    }

    private void lease(PartitionLease lease) {
        Objects.requireNonNull(lease);
        if (!definition.id().equals(lease.consumptionId())
                || lease.partitionId() >= definition.partitionCount())
            throw new IllegalArgumentException("foreign lease");
    }

    private Optional<PartitionLease> protect(PartitionLease lease) {
        ownership.bound(settings.lockTimeout(), settings.statementTimeout());
        return ownership.protectAcquisition(definition, lease);
    }

    private void fence(PartitionLease lease) {
        // Reuses the already retained partition lock; I3 reads a fresh PostgreSQL clock AFTER it.
        if (ownership.protectAcquisition(definition, lease).isEmpty())
            throw new IllegalStateException("Discovery ownership expired during admission");
    }

    private int budget(PartitionLease lease) {
        long count = admissions.liveSlots(lease);
        if (count < 0) throw new IllegalStateException("negative live stock");
        return (int) Math.min(settings.insertBatchSize(), Math.max(0, settings.softCap() - count));
    }

    private boolean local(UUID id, int partition) {
        return RoutingV1.partition(
                        RoutingV1.hash(definition.family(), id), definition.partitionCount())
                == partition;
    }

    private record WindowBounds(long afterExclusive, long highInclusive, int size, long expectedStart) {
        boolean isInconsistent(int maxWindowSize) {
            if (afterExclusive != expectedStart) return true;
            long span = highInclusive - expectedStart;
            return span > maxWindowSize || span != size;
        }
    }

    private <T> void validateWindow(
            WindowBounds bounds,
            List<T> references,
            ToLongFunction<T> positionOf,
            Function<T, UUID> idOf) {
        if (bounds.isInconsistent(settings.windowSize())) {
            throw new IllegalStateException("inconsistent dense global window");
        }
        long position = bounds.expectedStart();
        var ids = new HashSet<UUID>();
        for (var reference : references) {
            if (positionOf.applyAsLong(reference) != ++position || !ids.add(idOf.apply(reference))) {
                throw new IllegalStateException("hole or duplicate in global window");
            }
        }
    }

    private record RequestBatch(
            PartitionLease current, long expectedCheckpoint, int initialBudget) {}

    private record DiscoveryHandler<R>(
            java.util.function.BiFunction<Status, OptionalLong, R> earlyResult,
            Function<PartitionLease, R> onReady) {}

    public EventProgress discoverEvents(PartitionLease lease) {
        if (events == null) throw new IllegalStateException("not an event Discovery");
        lease(lease);
        long k = readCheckpoint(lease);
        var window = sourceReads.read(read -> events.window(k, settings.windowSize(), read));
        validateWindow(
                new WindowBounds(
                        window.afterExclusive(),
                        window.highInclusive(),
                        window.references().size(),
                        k),
                window.references(),
                NonBurnableReferences.Reference::position,
                NonBurnableReferences.Reference::eventId);
        // Decide stable exclusions outside the effects transaction; no mutable source reads there.
        var eligible =
                window.references().stream()
                        .map(r -> relevant.test(r) && local(r.eventId(), lease.partitionId()))
                        .toList();
        var handler =
                new DiscoveryHandler<EventProgress>(
                        (status, pos) -> new EventProgress(status, pos, 0, 0),
                        current -> admitEventBatch(current, k, window.references(), eligible));
        return effects.write(() -> executeDiscovery(lease, k, handler));
    }

    private EventProgress admitEventBatch(
            PartitionLease current,
            long k,
            List<NonBurnableReferences.Reference> references,
            List<Boolean> eligible) {
        int remaining = budget(current);
        if (references.isEmpty()) {
            return new EventProgress(Status.COMMITTED, OptionalLong.of(k), 0, 0);
        }
        return admitEventWindow(new RequestBatch(current, k, remaining), references, eligible);
    }

    private long readCheckpoint(PartitionLease lease) {
        long k =
                effects.read(
                        read -> admissions.checkpoint(definition.id(), lease.partitionId(), read));
        if (k < 0) throw new IllegalStateException("negative checkpoint");
        return k;
    }

    private <R> R executeDiscovery(
            PartitionLease lease, long k, DiscoveryHandler<R> handler) {
        var protectedLease = protect(lease);
        if (protectedLease.isEmpty()) {
            return handler.earlyResult().apply(Status.BUSY_OR_NOT_OWNER, OptionalLong.empty());
        }
        var current = protectedLease.orElseThrow();
        long actual = admissions.currentCheckpoint(current);
        if (actual != k) {
            fence(current);
            return handler.earlyResult().apply(Status.STALE_CHECKPOINT, OptionalLong.of(actual));
        }
        return handler.onReady().apply(current);
    }

    private EventProgress admitEventWindow(
            RequestBatch batch,
            List<NonBurnableReferences.Reference> references,
            List<Boolean> eligible) {
        int remaining = batch.initialBudget();
        int inserted = 0;
        int examined = 0;
        long covered = batch.expectedCheckpoint();
        boolean capacity = false;
        for (int i = 0; i < references.size(); i++) {
            var ref = references.get(i);
            examined++;
            if (eligible.get(i)) {
                if (!tryAdmitEvent(batch.current(), ref, remaining)) {
                    capacity = true;
                    break;
                }
                remaining--;
                inserted++;
            }
            covered = ref.position();
        }
        advanceCheckpointFenced(batch.current(), batch.expectedCheckpoint(), covered);
        return new EventProgress(
                capacity ? Status.CAPACITY : Status.COMMITTED,
                OptionalLong.of(covered),
                examined,
                inserted);
    }

    private boolean tryAdmitEvent(
            PartitionLease current, NonBurnableReferences.Reference ref, int remaining) {
        if (admissions.contains(definition, current.partitionId(), ref.eventId())) {
            throw new IllegalStateException("non-burnable slot above checkpoint");
        }
        fence(current);
        if (remaining == 0) return false;
        admissions.insertInitial(definition, current.partitionId(), ref.eventId());
        return true;
    }

    private void advanceCheckpointFenced(PartitionLease current, long expected, long covered) {
        fence(current);
        if (covered > expected) admissions.advanceCheckpoint(current, expected, covered);
        fence(current); // CAS/trigger/unique waits can also outlive the lease.
    }

    public RequestProgress discoverRequests(PartitionLease lease) {
        if (requests == null) throw new IllegalStateException("not a request Discovery");
        lease(lease);
        long k = readCheckpoint(lease);
        var window = sourceReads.read(read -> requests.window(k, settings.windowSize(), read));
        validateWindow(
                new WindowBounds(
                        window.afterExclusive(),
                        window.highInclusive(),
                        window.references().size(),
                        k),
                window.references(),
                BurnableReferences.Reference::position,
                BurnableReferences.Reference::requestId);
        var eligible =
                window.references().stream()
                        .map(r -> local(r.requestId(), lease.partitionId()))
                        .toList();
        var handler =
                new DiscoveryHandler<RequestProgress>(
                        (status, pos) -> new RequestProgress(status, pos, 0, 0, 0),
                        current -> admitRequestBatch(current, k, window.references(), eligible));
        return effects.write(() -> executeDiscovery(lease, k, handler));
    }

    private RequestProgress admitRequestBatch(
            PartitionLease current,
            long k,
            List<BurnableReferences.Reference> references,
            List<Boolean> eligible) {
        int remaining = budget(current);
        if (remaining == 0) {
            fence(current);
            return new RequestProgress(Status.CAPACITY, OptionalLong.of(k), 0, 0, 0);
        }
        if (references.isEmpty()) {
            return new RequestProgress(Status.COMMITTED, OptionalLong.of(k), 0, 0, 0);
        }
        return admitRequestWindow(new RequestBatch(current, k, remaining), references, eligible);
    }

    private enum RequestStep {
        SKIPPED,
        INSERTED,
        CAPACITY_REACHED,
        UNSETTLED
    }

    private RequestProgress admitRequestWindow(
            RequestBatch batch,
            List<BurnableReferences.Reference> references,
            List<Boolean> eligible) {
        int remaining = batch.initialBudget();
        int examined = 0;
        int advanced = 0;
        int inserted = 0;
        long covered = batch.expectedCheckpoint();
        boolean capacity = false;
        for (int i = 0; i < references.size(); i++) {
            var ref = references.get(i);
            examined++;
            if (eligible.get(i)) {
                var step = processEligibleRequest(batch.current(), ref.requestId(), remaining);
                if (step == RequestStep.CAPACITY_REACHED) {
                    capacity = true;
                    break;
                }
                if (step == RequestStep.UNSETTLED) break;
                if (step == RequestStep.INSERTED) {
                    remaining--;
                    inserted++;
                }
            }
            covered = ref.position();
            advanced++;
        }
        advanceCheckpointFenced(batch.current(), batch.expectedCheckpoint(), covered);
        return new RequestProgress(
                capacity ? Status.CAPACITY : Status.COMMITTED,
                OptionalLong.of(covered),
                examined,
                advanced,
                inserted);
    }

    private RequestStep processEligibleRequest(PartitionLease current, UUID id, int remaining) {
        if (!requests.tryProtectPending(id)) {
            boolean settled = isRequestSettled(current, id);
            fence(current);
            return settled ? RequestStep.SKIPPED : RequestStep.UNSETTLED;
        }
        listener.onCandidateDiscovered(definition, current.partitionId(), id, System.nanoTime());
        // A separate statement/snapshot AFTER source lock. No row lock on existing slot.
        boolean exists = admissions.contains(definition, current.partitionId(), id);
        fence(current);
        if (exists) return RequestStep.SKIPPED;
        if (remaining == 0) return RequestStep.CAPACITY_REACHED;
        admissions.insertInitial(definition, current.partitionId(), id);
        listener.onSlotAdmitted(definition, current.partitionId(), id, System.nanoTime());
        return RequestStep.INSERTED;
    }

    private boolean isRequestSettled(PartitionLease current, UUID id) {
        if (requests.isTerminal(id)) return true;
        if (admissions.contains(definition, current.partitionId(), id)) return true;
        return requests.isTerminal(id);
    }
}
