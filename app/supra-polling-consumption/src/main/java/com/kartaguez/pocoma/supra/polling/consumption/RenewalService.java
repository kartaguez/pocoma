package com.kartaguez.pocoma.supra.polling.consumption;
import com.kartaguez.pocoma.contract.consumption.invocation.ProtocolSafety;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.*;
import com.kartaguez.pocoma.supra.polling.consumption.wait.ConditionConsumptionWaiter;

import java.time.Duration;
import java.util.*;

/**
 * Independent bounded calls. An UNKNOWN call is never reassigned or settled as a business ticket.
 */
public final class RenewalService {
    private record CallSpec(
            Authority authority, int lane, long due, ProtocolSafety.RenewalPermit permit) {}

    static final class Call {
        final Authority authority;
        final int lane; // 0/1 singleton claims, 2 partition leases
        final ConsumptionWorker.Envelope envelope;
        final List<ConsumptionWorker.LeaseEntry> leases;
        final long began = System.nanoTime();
        final long due;
        final ProtocolSafety.RenewalPermit permit;
        boolean unknown;

        Call(
                CallSpec spec,
                ConsumptionWorker.Envelope envelope,
                List<ConsumptionWorker.LeaseEntry> leases) {
            this.due = spec.due();
            this.permit = Objects.requireNonNull(spec.permit());
            this.authority = spec.authority();
            this.lane = spec.lane();
            this.envelope = envelope;
            this.leases = List.copyOf(leases);
        }
    }

    private final ConsumptionWorker worker;
    private final List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<ConditionConsumptionWaiter> waiters =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final int[] nextClaim = new int[Authority.values().length];
    private volatile boolean stop;
    private int nextSettle;

    RenewalService(ConsumptionWorker worker) {
        this.worker = worker;
    }

    void start() {
        for (var authority : Authority.values()) {
            if (!hasAuthorityBindings(authority)) continue;
            for (int lane = 0; lane < 3; lane++) {
                threads.add(createLaneThread(authority, lane));
            }
        }
        threads.forEach(Thread::start);
    }

    private boolean hasAuthorityBindings(Authority authority) {
        return worker.bindings.stream().anyMatch(b -> b.definition().authority() == authority);
    }

    private Thread createLaneThread(Authority authority, int lane) {
        var waiter = new ConditionConsumptionWaiter();
        waiters.add(waiter);
        return ConsumptionWorker.namedFactory("v3-renew-" + authority + "-" + lane)
                .newThread(() -> runRenewalLane(authority, lane, waiter));
    }

    private void runRenewalLane(
            Authority authority, int lane, ConditionConsumptionWaiter waiter) {
        try {
            worker.awaitActivation();
            while (!stop) {
                boolean worked =
                        lane == 2
                                ? renewLeasesOnce(authority)
                                : renewClaimsOnce(authority, lane);
                waiter.await(worked ? worker.settings.minPoll() : idleRenewalDelay());
            }
        } catch (InterruptedException | RuntimeException | Error failure) {
            worker.requestDrain();
        }
    }

    private Duration idleRenewalDelay() {
        long quarter = worker.settings.renewalInterval().toMillis() / 4;
        return Duration.ofMillis(Math.max(1, Math.min(1000, quarter)));
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
        var retained = retainedEnvelopes(authority);
        long now = System.nanoTime();
        var call = claimNextRenewalCall(authority, lane, retained, now);
        if (call == null) return false;
        Throwable fault = null;
        try {
            // Renewal remains diagnostic: it never upserts a handle or authorizes execution.
            call.envelope.binding.engine().renewClaim(call.envelope.handle);
        } catch (RuntimeException | Error failure) {
            fault = failure;
        } finally {
            finish(call, worker.safety.finishRenewal(call.permit, fault));
        }
        return true;
    }

    private List<ConsumptionWorker.Envelope> retainedEnvelopes(Authority authority) {
        List<ConsumptionWorker.Envelope> snapshot;
        synchronized (worker.monitor) {
            snapshot = List.copyOf(worker.envelopes);
        }
        // Never acquire WorkerCapacity's lock while holding the local monitor.
        return snapshot.stream()
                .filter(
                        e ->
                                e.binding.definition().authority() == authority
                                        && e.binding.engine().retainsCapacity(e.handle))
                .toList();
    }

    private Call claimNextRenewalCall(
            Authority authority,
            int lane,
            List<ConsumptionWorker.Envelope> retained,
            long now) {
        synchronized (worker.monitor) {
            if (!worker.renewalsAllowed || isLaneBusy(authority, lane)) return null;
            for (int i = 0; i < retained.size(); i++) {
                int index = Math.floorMod(nextClaim[authority.ordinal()] + i, retained.size());
                var e = retained.get(index);
                if (!isClaimEligible(e, now)) continue;
                var permit = worker.safety.beginRenewal(authority, lane, e.handle);
                if (permit.isEmpty()) continue;
                long due = e.renewedAt + worker.settings.renewalInterval().toNanos();
                var call = new Call(new CallSpec(authority, lane, due, permit.get()), e, List.of());
                worker.renewalCalls.add(call);
                e.renewedAt = now;
                nextClaim[authority.ordinal()] = index + 1;
                return call;
            }
            return null;
        }
    }

    private boolean isLaneBusy(Authority authority, int lane) {
        return worker.renewalCalls.stream()
                .anyMatch(c -> c.authority == authority && c.lane == lane);
    }

    private boolean isClaimEligible(ConsumptionWorker.Envelope e, long now) {
        if (!worker.envelopes.contains(e)) return false;
        if (e.phase == ConsumptionWorker.Phase.ABANDONED) return false;
        if (now - e.renewedAt < worker.settings.renewalInterval().toNanos()) return false;
        return worker.renewalCalls.stream().noneMatch(c -> c.envelope == e);
    }

    private long leaseDue(ConsumptionWorker.LeaseEntry entry) {
        return entry.retryUntil == 0
                ? entry.renewedAt + worker.settings.renewalInterval().toNanos()
                : entry.retryDue;
    }

    public boolean renewLeasesOnce(Authority authority) {
        long now = System.nanoTime();
        var call = claimLeaseBatchCall(authority, now);
        if (call == null) return false;
        Throwable fault = null;
        List<ConsumptionProtocol.Partition> result = List.of();
        try {
            result = renewPartitionBatch(call);
        } catch (RuntimeException | Error failure) {
            fault = failure;
        } finally {
            var verdict = worker.safety.finishRenewal(call.permit, fault);
            int suspended = settleLeaseBatch(call, result, verdict, now);
            if (suspended > 0) {
                worker.event(
                        call.leases.getFirst().pair.binding(),
                        Phase.LEASE_SUSPEND,
                        Outcome.BUSY,
                        suspended,
                        Duration.ZERO);
            }
            finish(call, verdict);
        }
        return true;
    }

    private Call claimLeaseBatchCall(Authority authority, long now) {
        synchronized (worker.monitor) {
            if (!worker.renewalsAllowed || !worker.running()) return null;
            if (isLaneBusy(authority, 2)) return null;
            var batch = selectDueLeaseBatch(authority, now);
            if (batch.isEmpty()) return null;
            var permit = worker.safety.beginRenewal(authority, 2, null);
            if (permit.isEmpty()) return null;
            long minDue = batch.stream().mapToLong(this::leaseDue).min().orElseThrow();
            var call = new Call(new CallSpec(authority, 2, minDue, permit.get()), null, batch);
            worker.renewalCalls.add(call);
            for (var e : batch) e.renewedAt = now;
            return call;
        }
    }

    private List<ConsumptionWorker.LeaseEntry> selectDueLeaseBatch(Authority authority, long now) {
        long intervalNanos = worker.settings.renewalInterval().toNanos();
        var due =
                worker.leases.values().stream()
                        .filter(
                                e ->
                                        e.pair.binding().definition().authority() == authority
                                                && now - e.renewedAt >= intervalNanos)
                        .toList();
        if (due.isEmpty()) return List.of();
        // A serviced lease can become due again before a slow tour finishes. Age the
        // bounded candidates, including within a binding, so it cannot jump ahead again.
        var oldest = Comparator.comparingLong((ConsumptionWorker.LeaseEntry e) -> e.renewedAt);
        var sortedDue = due.stream().sorted(oldest).toList();
        var firstBinding = sortedDue.getFirst().pair.binding();
        var stream =
                firstBinding.engine().supportsCrossDefinitionRenewal()
                        ? sortedDue.stream()
                        : sortedDue.stream().filter(e -> e.pair.binding().equals(firstBinding));
        return stream.limit(worker.settings.pageSize()).toList();
    }

    private static List<ConsumptionProtocol.Partition> renewPartitionBatch(Call call) {
        var definitions =
                call.leases.stream().map(e -> e.pair.binding().definition()).distinct().toList();
        return List.copyOf(
                call.leases
                        .getFirst()
                        .pair
                        .binding()
                        .engine()
                        .renewPartitionBatch(
                                definitions, call.leases.stream().map(e -> e.lease).toList()));
    }

    private int settleLeaseBatch(
            Call call,
            List<ConsumptionProtocol.Partition> result,
            ProtocolSafety.PhysicalOutcome verdict,
            long now) {
        int suspended = 0;
        synchronized (worker.monitor) {
            for (var entry : call.leases) {
                if (worker.leases.get(entry.pair) != entry) continue;
                var decision =
                        worker.safety.settleLease(
                                entry.lease,
                                result,
                                verdict,
                                worker.running(),
                                entry.usable,
                                entry.retryUntil,
                                entry.retryDue,
                                call.due,
                                now,
                                entry.renewedAt,
                                worker.settings.renewalBudget(),
                                worker.settings.renewalInterval(),
                                worker.settings.minPoll());
                applyLeaseDecision(entry, decision);
                if (decision.suspended()) suspended++;
            }
        }
        return suspended;
    }

    private void applyLeaseDecision(
            ConsumptionWorker.LeaseEntry entry, ProtocolSafety.LeaseDecision decision) {
        if (decision.remove()) {
            worker.leases.remove(entry.pair);
            return;
        }
        entry.lease = decision.lease();
        entry.usable = decision.usable();
        entry.retryUntil = decision.retryUntil();
        entry.retryDue = decision.retryDue();
        entry.renewedAt = decision.renewedAt();
    }

    private void finish(Call call, ProtocolSafety.PhysicalOutcome verdict) {
        boolean certain = verdict.physicallyResolved();
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
                renewalPhase(call.lane),
                outcome,
                1,
                Duration.ofNanos(System.nanoTime() - call.due));
        worker.event(
                binding,
                renewalLagPhase(call.lane),
                outcome,
                1,
                Duration.ofNanos(call.began - call.due));
        if (verdict.requestDrain()) worker.requestDrain();
        worker.maintenanceWait.signal();
    }

    private static Phase renewalPhase(int lane) {
        return switch (lane) {
            case 0 -> Phase.RENEWAL_S1;
            case 1 -> Phase.RENEWAL_S2;
            default -> Phase.RENEWAL_LEASE;
        };
    }

    private static Phase renewalLagPhase(int lane) {
        return switch (lane) {
            case 0 -> Phase.RENEWAL_LAG_S1;
            case 1 -> Phase.RENEWAL_LAG_S2;
            default -> Phase.RENEWAL_LAG_LEASE;
        };
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
