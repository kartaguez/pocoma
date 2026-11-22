package com.kartaguez.pocoma.supra.polling.consumption;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.*;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/** One bounded C/p opportunity, independent of business threads and capacity. */
public final class DiscoveryLoop {
    private final ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final long[] errorUntilNanos;
    private int nextConsumption;
    private final Set<ConsumptionWorker.Pair> saturated = new HashSet<>();
    private final Map<ConsumptionWorker.Pair, Long> pairCheckpoints = new HashMap<>();
    private final Map<ConsumableFamily, Long> emptyFamilyCheckpointThisTour = new EnumMap<>(ConsumableFamily.class);
    private final AtomicInteger pendingWakeupPairs = new AtomicInteger();
    private final AtomicLongArray wakeupReceivedNanos = new AtomicLongArray(32);
    private final AtomicBoolean fullReconcileRequested = new AtomicBoolean();
    private int tourRemaining;

    DiscoveryLoop(ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
        errorUntilNanos = new long[worker.bindings.size()];
        this.tourRemaining =
                worker.bindings.size()
                        * worker.bindings.stream()
                                .mapToInt(b -> b.partitions().size())
                                .max()
                                .orElse(1);
    }

    void onExternalWakeup(ConsumableFamily family, int partition) {
        if (family == null || partition < 0 || partition >= 4) return;
        int mask = 0;
        for (int i = 0; i < worker.bindings.size() && i < 8; i++) {
            var b = worker.bindings.get(i);
            if (b.definition().family() == family && b.partitions().contains(partition)) {
                mask |= (1 << (i * 4 + partition));
            }
        }
        if (mask != 0) {
            long now = System.nanoTime();
            int remaining = mask;
            while (remaining != 0) {
                int bit = Integer.numberOfTrailingZeros(remaining);
                wakeupReceivedNanos.compareAndSet(bit, 0L, now);
                remaining &= ~(1 << bit);
            }
            worker.wakeupMetrics.onReceived();
            pendingWakeupPairs.getAndAccumulate(mask, (a, b) -> a | b);
            worker.discoveryWait.signal();
        }
    }

    void onExternalReconnect() {
        fullReconcileRequested.set(true);
        worker.discoveryWait.signal();
    }

    boolean hasPendingWakeup() {
        return pendingWakeupPairs.get() != 0 || fullReconcileRequested.get();
    }

    private int pollPendingWakeupBit() {
        while (true) {
            int mask = pendingWakeupPairs.get();
            if (mask == 0) return -1;
            int bit = Integer.numberOfTrailingZeros(mask);
            if (pendingWakeupPairs.compareAndSet(mask, mask & ~(1 << bit))) {
                return bit;
            }
        }
    }

    boolean hasReadyBinding() {
        long now = System.nanoTime();
        for (long until : errorUntilNanos) if (now - until >= 0) return true;
        return false;
    }

    private static final class DiscoveryState {
        Outcome outcome = Outcome.UNKNOWN;
        Throwable failure;
    }

    public boolean runOnePass() {
        if (!worker.beginOperation()) return false;
        long began = System.nanoTime();
        if (fullReconcileRequested.compareAndSet(true, false)) {
            emptyFamilyCheckpointThisTour.clear();
        }
        int wakeupBit = pollPendingWakeupBit();
        boolean fromWakeup = wakeupBit >= 0;
        int selected;
        ConsumptionWorker.Binding binding;
        int p;
        if (fromWakeup) {
            long receivedAt = wakeupReceivedNanos.getAndSet(wakeupBit, 0L);
            if (receivedAt > 0L) {
                worker.wakeupMetrics.onDiscoveryDelay(began - receivedAt);
            }
            selected = wakeupBit / 4;
            p = wakeupBit % 4;
            binding = worker.bindings.get(selected);
            emptyFamilyCheckpointThisTour.remove(binding.definition().family());
        } else {
            selected = selectBindingIndex(began);
            binding = worker.bindings.get(selected);
            nextConsumption = (selected + 1) % worker.bindings.size();
            p = advancePartition(selected, binding);
        }
        var state = new DiscoveryState();
        worker.event(binding, Phase.DISCOVERY_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
        try {
            if (fromWakeup && worker.lease(binding, p) == null) {
                worker.wakeupMetrics.onWakeupIgnoredNoLease();
                state.outcome = Outcome.CONFIRMED;
                return false;
            }
            boolean progressed = executeDiscoveryPass(selected, binding, p, state);
            if (fromWakeup) {
                if (progressed) worker.wakeupMetrics.onWakeupUseful();
                else worker.wakeupMetrics.onWakeupSpurious();
            }
            return progressed;
        } catch (RuntimeException | Error exit) {
            state.failure = exit;
            errorUntilNanos[selected] =
                    System.nanoTime() + worker.settings.errorPoll().toNanos();
            throw exit;
        } finally {
            worker.event(
                    binding,
                    Phase.DISCOVERY,
                    state.outcome,
                    1,
                    Duration.ofNanos(System.nanoTime() - began));
            worker.endOperation(state.failure);
        }
    }

    private int selectBindingIndex(long began) {
        if (worker.bindings.size() <= 1) return nextConsumption;
        for (int i = 0; i < worker.bindings.size(); i++) {
            int candidate = (nextConsumption + i) % worker.bindings.size();
            if (began - errorUntilNanos[candidate] >= 0) return candidate;
        }
        return nextConsumption;
    }

    private int advancePartition(int selected, ConsumptionWorker.Binding binding) {
        int p = binding.partitions().get(nextPartitions[selected]);
        nextPartitions[selected] = (nextPartitions[selected] + 1) % binding.partitions().size();
        return p;
    }

    private boolean executeDiscoveryPass(
            int selected, ConsumptionWorker.Binding binding, int p, DiscoveryState state) {
        var pair = new ConsumptionWorker.Pair(binding, p);
        var family = binding.definition().family();
        if (--tourRemaining <= 0) {
            emptyFamilyCheckpointThisTour.clear();
            tourRemaining =
                    worker.bindings.size()
                            * worker.bindings.stream()
                                    .mapToInt(b -> b.partitions().size())
                                    .max()
                                    .orElse(1);
        }
        Long knownK = pairCheckpoints.get(pair);
        if (knownK != null && knownK.equals(emptyFamilyCheckpointThisTour.get(family))) {
            state.outcome = Outcome.CONFIRMED;
            return false;
        }
        var result = discoverLocked(binding, pair, p);
        if (result == null) {
            pairCheckpoints.remove(pair);
            emptyFamilyCheckpointThisTour.remove(family);
            return false;
        }
        if (result.checkpoint().isPresent()) {
            long cp = result.checkpoint().getAsLong();
            pairCheckpoints.put(pair, cp);
            if (result.status() == DiscoveryReports.Status.COMMITTED
                    && result.examined() == 0
                    && result.committedAdmissions() == 0) {
                emptyFamilyCheckpointThisTour.put(family, cp);
            } else {
                emptyFamilyCheckpointThisTour.remove(family);
            }
        } else {
            pairCheckpoints.remove(pair);
            emptyFamilyCheckpointThisTour.remove(family);
        }
        if (result.committedAdmissions() > 0) {
            worker.markPairDirty(pair);
            worker.consumptionWait.signal();
        }
        updateSoftCapSaturation(binding, pair, result.status());
        state.outcome = mapDiscoveryOutcome(result.status());
        recordDiscoveryCounts(binding, result);
        errorUntilNanos[selected] = 0;
        return hasDiscoveryProgress(result);
    }

    private DiscoveryReports.Progress discoverLocked(
            ConsumptionWorker.Binding binding, ConsumptionWorker.Pair pair, int partition) {
        synchronized (worker.pairLock(pair)) {
            var lease = worker.lease(binding, partition);
            if (lease == null) return null;
            var result = binding.protocol().discover(lease, false);
            if (result.status() == DiscoveryReports.Status.BUSY_OR_NOT_OWNER) {
                worker.suspend(binding, lease);
            }
            return result;
        }
    }

    private void updateSoftCapSaturation(
            ConsumptionWorker.Binding binding,
            ConsumptionWorker.Pair pair,
            DiscoveryReports.Status status) {
        if (status != DiscoveryReports.Status.CAPACITY) {
            saturated.remove(pair);
            return;
        }
        if (saturated.add(pair)) {
            worker.event(binding, Phase.SATURATION_SOFTCAP, Outcome.CAPACITY, 1, Duration.ZERO);
        }
    }

    private static Outcome mapDiscoveryOutcome(DiscoveryReports.Status status) {
        return switch (status) {
            case CAPACITY -> Outcome.CAPACITY;
            case BUSY_OR_NOT_OWNER -> Outcome.BUSY;
            default -> Outcome.CONFIRMED;
        };
    }

    private void recordDiscoveryCounts(
            ConsumptionWorker.Binding binding, DiscoveryReports.Progress result) {
        worker.event(
                binding, Phase.DISCOVERY_EXAMINED, Outcome.CONFIRMED, result.examined(), Duration.ZERO);
        worker.event(
                binding, Phase.DISCOVERY_ADVANCED, Outcome.CONFIRMED, result.advanced(), Duration.ZERO);
        worker.event(
                binding,
                Phase.DISCOVERY_ADMITTED,
                Outcome.CONFIRMED,
                result.committedAdmissions(),
                Duration.ZERO);
    }

    private static boolean hasDiscoveryProgress(DiscoveryReports.Progress result) {
        if (result.committedAdmissions() > 0 || result.advanced() > 0) return true;
        return result.status() == DiscoveryReports.Status.COMMITTED && result.examined() > 0;
    }
}
