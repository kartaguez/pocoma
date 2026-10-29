package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import java.time.Duration;
import java.util.*;

/**
 * Independent bounded calls. An UNKNOWN call is never reassigned or settled as a business ticket.
 */
public final class V3RenewalService {
    static final class Call {
        final Authority authority;
        final int lane; // 0/1 singleton claims, 2 partition leases
        final V3ConsumptionWorker.Envelope envelope;
        final List<V3ConsumptionWorker.LeaseEntry> leases;
        final long began = System.nanoTime();
        final long due;
        boolean unknown;

        Call(
                Authority authority,
                int lane,
                V3ConsumptionWorker.Envelope envelope,
                List<V3ConsumptionWorker.LeaseEntry> leases,
                long due) {
            this.due = due;
            this.authority = authority;
            this.lane = lane;
            this.envelope = envelope;
            this.leases = List.copyOf(leases);
        }
    }

    private final V3ConsumptionWorker worker;
    private final List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<ConditionConsumptionWaiter> waiters =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int[] nextClaim = new int[Authority.values().length];
    private volatile boolean stop;
    private int nextSettle;

    V3RenewalService(V3ConsumptionWorker worker) {
        this.worker = worker;
    }

    void start() {
        for (var authority : Authority.values()) {
            if (worker.bindings.stream().noneMatch(b -> b.definition().authority() == authority))
                continue;
            for (int lane = 0; lane < 3; lane++) {
                int selected = lane;
                var waiter = new ConditionConsumptionWaiter();
                waiters.add(waiter);
                var thread =
                        V3ConsumptionWorker.namedFactory("v3-renew-" + authority + "-" + lane)
                                .newThread(
                                        () -> {
                                            try {
                                                worker.awaitActivation();
                                                while (!stop) {
                                                    boolean worked =
                                                            selected == 2
                                                                    ? renewLeasesOnce(authority)
                                                                    : renewClaimsOnce(
                                                                            authority, selected);
                                                    waiter.await(
                                                            worked
                                                                    ? worker.settings.minPoll()
                                                                    : Duration.ofMillis(
                                                                            Math.max(
                                                                                    1,
                                                                                    Math.min(
                                                                                            1000,
                                                                                            worker.settings
                                                                                                            .renewalInterval()
                                                                                                            .toMillis()
                                                                                                    / 4))));
                                                }
                                            } catch (InterruptedException
                                                    | RuntimeException
                                                    | Error failure) {
                                                worker.requestDrain();
                                            }
                                        });
                threads.add(thread);
            }
        }
        threads.forEach(Thread::start);
    }

    void signal() {
        for (var waiter : waiters) waiter.signal();
    }

    void stopAndJoin() throws InterruptedException {
        stop = true;
        signal();
        for (var thread : threads) thread.join();
    }

    public boolean renewClaimsOnce(Authority authority, int lane) {
        if (lane < 0 || lane > 1) throw new IllegalArgumentException("invalid claim lane");
        List<V3ConsumptionWorker.Envelope> snapshot;
        synchronized (worker.monitor) {
            snapshot = List.copyOf(worker.envelopes);
        }
        // Never acquire WorkerCapacity's lock while holding the local monitor.
        var retained =
                snapshot.stream()
                        .filter(
                                e ->
                                        e.binding.definition().authority() == authority
                                                && e.binding.engine().retainsCapacity(e.handle))
                        .toList();
        Call call = null;
        long now = System.nanoTime();
        synchronized (worker.monitor) {
            if (!worker.renewalsAllowed
                    || worker.renewalCalls.stream()
                            .anyMatch(c -> c.authority == authority && c.lane == lane))
                return false;
            for (int i = 0; i < retained.size(); i++) {
                int index = Math.floorMod(nextClaim[authority.ordinal()] + i, retained.size());
                var e = retained.get(index);
                if (!worker.envelopes.contains(e)
                        || e.phase == V3ConsumptionWorker.Phase.ABANDONED
                        || now - e.renewedAt < worker.settings.renewalInterval().toNanos()
                        || worker.renewalCalls.stream().anyMatch(c -> c.envelope == e)) continue;
                call =
                        new Call(
                                authority,
                                lane,
                                e,
                                List.of(),
                                e.renewedAt + worker.settings.renewalInterval().toNanos());
                worker.renewalCalls.add(call);
                e.renewedAt = now;
                nextClaim[authority.ordinal()] = index + 1;
                break;
            }
        }
        if (call == null) return false;
        boolean certain = false;
        try {
            // Result is diagnostic only: it cannot upsert handles, release tickets or authorize
            // execution.
            call.envelope.binding.engine().renewClaims(List.of(call.envelope.handle.claim()));
            certain = true;
        } catch (Failure failure) {
            certain = failure.resolution() != Resolution.UNKNOWN;
        } catch (RuntimeException | Error failure) {
            /* Unknown: keep call and candidate excluded. */
        } finally {
            finish(call, certain);
        }
        return true;
    }

    public boolean renewLeasesOnce(Authority authority) {
        Call call = null;
        long now = System.nanoTime();
        synchronized (worker.monitor) {
            if (!worker.renewalsAllowed
                    || !worker.running()
                    || worker.renewalCalls.stream()
                            .anyMatch(c -> c.authority == authority && c.lane == 2)) return false;
            var due =
                    worker.leases.values().stream()
                            .filter(
                                    e ->
                                            e.pair.binding().definition().authority() == authority
                                                    && now - e.renewedAt
                                                            >= worker.settings
                                                                    .renewalInterval()
                                                                    .toNanos())
                            .toList();
            if (due.isEmpty()) return false;
            // A serviced lease can become due again before a slow tour finishes. Age the
            // bounded candidates, including within a binding, so it cannot jump ahead again.
            var oldest =
                    Comparator.comparingLong((V3ConsumptionWorker.LeaseEntry e) -> e.renewedAt);
            var binding = due.stream().min(oldest).orElseThrow().pair.binding();
            var batch =
                    due.stream()
                            .filter(e -> e.pair.binding() == binding)
                            .sorted(oldest)
                            .limit(worker.settings.pageSize())
                            .toList();
            call =
                    new Call(
                            authority,
                            2,
                            null,
                            batch,
                            batch.stream().mapToLong(e -> e.renewedAt).min().orElseThrow()
                                    + worker.settings.renewalInterval().toNanos());
            worker.renewalCalls.add(call);
            for (var e : batch) e.renewedAt = now;
        }
        boolean certain = false;
        List<PartitionLease> result = List.of();
        try {
            result =
                    call.leases
                            .getFirst()
                            .pair
                            .binding()
                            .engine()
                            .renewPartitions(call.leases.stream().map(e -> e.lease).toList());
            certain = true;
        } catch (Failure failure) {
            certain = failure.resolution() != Resolution.UNKNOWN;
        } catch (RuntimeException | Error failure) {
            /* No inferred physical resolution. */
        } finally {
            synchronized (worker.monitor) {
                for (var entry : call.leases) {
                    if (worker.leases.get(entry.pair) != entry) continue;
                    var renewed =
                            result.stream()
                                    .filter(
                                            l ->
                                                    l.consumptionId()
                                                                    .equals(
                                                                            entry.lease
                                                                                    .consumptionId())
                                                            && l.partitionId()
                                                                    == entry.lease.partitionId()
                                                            && l.ownerToken()
                                                                    .equals(
                                                                            entry.lease
                                                                                    .ownerToken()))
                                    .findFirst();
                    if (renewed.isPresent() && certain && worker.running()) {
                        entry.lease = renewed.get();
                        entry.usable = true;
                    } else
                        worker.leases.remove(
                                entry.pair); // Never reinsert an incarnation removed during this
                    // call.
                }
            }
            finish(call, certain);
        }
        return true;
    }

    private void finish(Call call, boolean certain) {
        synchronized (worker.monitor) {
            if (certain) worker.renewalCalls.remove(call);
            else call.unknown = true;
        }
        var binding =
                call.envelope != null
                        ? call.envelope.binding
                        : call.leases.getFirst().pair.binding();
        var outcome = certain ? Outcome.CONFIRMED : Outcome.UNKNOWN;
        worker.event(
                binding,
                switch (call.lane) {
                    case 0 -> Phase.RENEWAL_S1;
                    case 1 -> Phase.RENEWAL_S2;
                    default -> Phase.RENEWAL_LEASE;
                },
                outcome,
                1,
                Duration.ofNanos(System.nanoTime() - call.due));
        worker.event(
                binding,
                switch (call.lane) {
                    case 0 -> Phase.RENEWAL_LAG_S1;
                    case 1 -> Phase.RENEWAL_LAG_S2;
                    default -> Phase.RENEWAL_LAG_LEASE;
                },
                outcome,
                1,
                Duration.ofNanos(call.began - call.due));
        if (!certain) worker.requestDrain();
        worker.maintenanceWait.signal();
    }

    public void settleOnce() {
        // This single maintenance lane cannot be reused while its prior physical unit is unknown.
        if (worker.recoveryUncertain()) {
            worker.requestDrain();
            return;
        }
        var binding = worker.bindings.get(nextSettle);
        nextSettle = (nextSettle + 1) % worker.bindings.size();
        try {
            binding.engine().settlePending();
        } catch (RuntimeException | Error fault) {
            worker.requestDrain();
        }
        if (worker.recoveryUncertain()) worker.requestDrain();
        worker.prune();
    }

    void checkDeadlines() {
        boolean exceeded;
        long now = System.nanoTime();
        synchronized (worker.monitor) {
            exceeded =
                    worker.renewalCalls.stream()
                            .anyMatch(
                                    c -> now - c.began > worker.settings.renewalBudget().toNanos());
        }
        if (exceeded) worker.requestDrain();
    }
}
