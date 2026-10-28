package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/** Bounded Discovery only. Durable slots coordinate it with the independent lifecycle Engine. */
public final class DiscoveryEngine {
    public record Settings(
            int windowSize,
            int pageSize,
            int insertBatchSize,
            long softCap,
            Duration lockTimeout,
            Duration statementTimeout) {
        public Settings {
            if (windowSize < 1 || pageSize < 1 || insertBatchSize < 1 || softCap < 1)
                throw new IllegalArgumentException("positive bounds required");
            LifecycleSettings.micros(lockTimeout, false);
            LifecycleSettings.micros(statementTimeout, false);
            if (lockTimeout.toMillis() < 1
                    || statementTimeout.toMillis() < 1
                    || lockTimeout.compareTo(statementTimeout) > 0)
                throw new IllegalArgumentException("invalid SQL timeout bounds");
        }
    }

    public record RequestWalk(Optional<UUID> afterExclusive, Optional<UUID> upperInclusive) {
        public RequestWalk {
            Objects.requireNonNull(afterExclusive);
            Objects.requireNonNull(upperInclusive);
            if (afterExclusive.isPresent()
                    && (upperInclusive.isEmpty()
                            || compare(afterExclusive.orElseThrow(), upperInclusive.orElseThrow())
                                    > 0))
                throw new IllegalArgumentException("cursor outside UUID tour");
        }

        public static RequestWalk fresh() {
            return new RequestWalk(Optional.empty(), Optional.empty());
        }
    }

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
            RequestWalk next,
            int examined,
            int advanced,
            int committedAdmissions,
            boolean tourFinished) {}

    private final ConsumptionDefinition definition;
    private final AttemptTransactions effects;
    private final PartitionOwnershipStore ownership;
    private final DiscoveryAdmissionStore admissions;
    private final Settings settings;
    private final AttemptTransactions sourceReads;
    private final NonBurnableReferences events;
    private final BurnableReferences requests;
    private final Predicate<NonBurnableReferences.Reference> relevant;

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            Settings settings,
            AttemptTransactions sourceReads,
            NonBurnableReferences source,
            Predicate<NonBurnableReferences.Reference> relevant) {
        this.definition = Objects.requireNonNull(definition);
        this.effects = Objects.requireNonNull(effects);
        this.ownership = Objects.requireNonNull(ownership);
        this.admissions = Objects.requireNonNull(admissions);
        this.settings = Objects.requireNonNull(settings);
        this.sourceReads = Objects.requireNonNull(sourceReads);
        this.events = Objects.requireNonNull(source);
        this.relevant = Objects.requireNonNull(relevant);
        this.requests = null;
        if (burnable(definition.family()) || definition.family() != source.family())
            throw new IllegalArgumentException("incompatible event source");
    }

    public DiscoveryEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effects,
            PartitionOwnershipStore ownership,
            DiscoveryAdmissionStore admissions,
            Settings settings,
            BurnableReferences source) {
        this.definition = Objects.requireNonNull(definition);
        this.effects = Objects.requireNonNull(effects);
        this.ownership = Objects.requireNonNull(ownership);
        this.admissions = Objects.requireNonNull(admissions);
        this.settings = Objects.requireNonNull(settings);
        this.sourceReads = effects;
        this.requests = Objects.requireNonNull(source);
        this.events = null;
        this.relevant = null;
        if (!burnable(definition.family()) || definition.family() != source.family())
            throw new IllegalArgumentException("incompatible request source");
    }

    private static boolean burnable(ConsumableFamily family) {
        return family == ConsumableFamily.COMMAND || family == ConsumableFamily.IDENTITY_REQUEST;
    }

    // PostgreSQL uuid compares the 16 unsigned network-order bytes, unlike UUID.compareTo.
    private static int compare(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0
                ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
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

    private void validate(NonBurnableReferences.Window window, long k) {
        if (window.afterExclusive() != k
                || window.highInclusive() - k > settings.windowSize()
                || window.highInclusive() - k != window.references().size())
            throw new IllegalStateException("inconsistent dense global window");
        long position = k;
        var ids = new HashSet<UUID>();
        for (var reference : window.references()) {
            if (reference.position() != ++position || !ids.add(reference.eventId()))
                throw new IllegalStateException("hole or duplicate in global window");
        }
    }

    public EventProgress discoverEvents(PartitionLease lease) {
        if (events == null) throw new IllegalStateException("not an event Discovery");
        lease(lease);
        long k =
                effects.read(
                        read -> admissions.checkpoint(definition.id(), lease.partitionId(), read));
        if (k < 0) throw new IllegalStateException("negative checkpoint");
        var window = sourceReads.read(read -> events.window(k, settings.windowSize(), read));
        validate(window, k);
        // Decide stable exclusions outside the effects transaction; no mutable source reads there.
        var eligible =
                window.references().stream()
                        .map(r -> relevant.test(r) && local(r.eventId(), lease.partitionId()))
                        .toList();
        return effects.write(
                () -> {
                    var protectedLease = protect(lease);
                    if (protectedLease.isEmpty())
                        return new EventProgress(
                                Status.BUSY_OR_NOT_OWNER, OptionalLong.empty(), 0, 0);
                    var current = protectedLease.orElseThrow();
                    long actual = admissions.currentCheckpoint(current);
                    if (actual != k) {
                        fence(current);
                        return new EventProgress(
                                Status.STALE_CHECKPOINT, OptionalLong.of(actual), 0, 0);
                    }
                    int remaining = budget(current), inserted = 0, examined = 0;
                    long covered = k;
                    boolean capacity = false;
                    for (int i = 0; i < window.references().size(); i++) {
                        var ref = window.references().get(i);
                        examined++;
                        if (eligible.get(i)) {
                            if (admissions.contains(
                                    definition, current.partitionId(), ref.eventId()))
                                throw new IllegalStateException(
                                        "non-burnable slot above checkpoint");
                            fence(current);
                            if (remaining == 0) {
                                capacity = true;
                                break;
                            }
                            admissions.insertInitial(
                                    definition, current.partitionId(), ref.eventId());
                            remaining--;
                            inserted++;
                        }
                        covered = ref.position();
                    }
                    fence(current);
                    if (covered > k) admissions.advanceCheckpoint(current, k, covered);
                    fence(current); // CAS/trigger/unique waits can also outlive the lease.
                    return new EventProgress(
                            capacity ? Status.CAPACITY : Status.COMMITTED,
                            OptionalLong.of(covered),
                            examined,
                            inserted);
                }); // No result escapes if physical resolution fails, even COMMITTED with lost
        // callback.
    }

    public RequestProgress discoverRequests(PartitionLease lease, RequestWalk walk) {
        if (requests == null) throw new IllegalStateException("not a request Discovery");
        lease(lease);
        Objects.requireNonNull(walk);
        var upper =
                walk.upperInclusive().isPresent()
                        ? walk.upperInclusive()
                        : sourceReads.read(requests::upperPending);
        var page =
                upper.isEmpty()
                        ? List.<UUID>of()
                        : sourceReads.read(
                                read ->
                                        List.copyOf(
                                                requests.pendingPage(
                                                        walk.afterExclusive(),
                                                        upper.orElseThrow(),
                                                        settings.pageSize(),
                                                        read)));
        if (page.size() > settings.pageSize())
            throw new IllegalStateException("oversized request page");
        UUID previous = walk.afterExclusive().orElse(null);
        for (var id : page) {
            if (previous != null && compare(id, previous) <= 0
                    || upper.isEmpty()
                    || compare(id, upper.orElseThrow()) > 0)
                throw new IllegalStateException("unordered request page outside tour");
            previous = id;
        }
        return effects.write(
                () -> {
                    var protectedLease = protect(lease);
                    if (protectedLease.isEmpty())
                        return new RequestProgress(Status.BUSY_OR_NOT_OWNER, walk, 0, 0, 0, false);
                    var current = protectedLease.orElseThrow();
                    int remaining = budget(current);
                    if (remaining == 0) {
                        fence(current);
                        return new RequestProgress(Status.CAPACITY, walk, 0, 0, 0, false);
                    }
                    var after = walk.afterExclusive();
                    int examined = 0, advanced = 0, inserted = 0;
                    boolean capacity = false;
                    for (var id : page) {
                        examined++;
                        if (local(id, current.partitionId()) && requests.tryProtectPending(id)) {
                            // A separate statement/snapshot AFTER source lock. No row lock on
                            // existing slot.
                            boolean exists =
                                    admissions.contains(definition, current.partitionId(), id);
                            fence(current);
                            if (!exists) {
                                if (remaining == 0) {
                                    capacity = true;
                                    break;
                                }
                                admissions.insertInitial(definition, current.partitionId(), id);
                                remaining--;
                                inserted++;
                            }
                        }
                        after = Optional.of(id);
                        advanced++;
                    }
                    fence(current);
                    boolean finished = !capacity && (page.isEmpty() || after.equals(upper));
                    var next = finished ? RequestWalk.fresh() : new RequestWalk(after, upper);
                    return new RequestProgress(
                            capacity ? Status.CAPACITY : Status.COMMITTED,
                            next,
                            examined,
                            advanced,
                            inserted,
                            finished);
                });
    }
}
