package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Local lifecycle only. Engine alone owns all reservations and durable decisions. */
public final class V3ConsumptionWorker {
    public enum State {
        STARTING,
        RUNNING,
        DRAINING,
        STOPPED
    }

    public record Binding(
            ConsumptionDefinition definition,
            ConsumptionEngine engine,
            DiscoveryEngine discovery,
            List<Integer> partitions) {
        public Binding {
            Objects.requireNonNull(definition);
            Objects.requireNonNull(engine);
            Objects.requireNonNull(discovery);
            partitions = List.copyOf(partitions);
            if (partitions.isEmpty()
                    || new HashSet<>(partitions).size() != partitions.size()
                    || partitions.stream().anyMatch(p -> p < 0 || p >= definition.partitionCount()))
                throw new IllegalArgumentException("invalid candidate partitions");
        }
    }

    public record Settings(
            int pageSize,
            int quantum,
            Duration minPoll,
            Duration maxPoll,
            Duration errorPoll,
            Duration settleInterval,
            Duration renewalInterval,
            Duration renewalBudget,
            Duration drainDeadline) {
        public Settings {
            if (pageSize < 1 || quantum < 1 || quantum > pageSize)
                throw new IllegalArgumentException("invalid polling bounds");
            for (var d :
                    List.of(
                            minPoll,
                            maxPoll,
                            errorPoll,
                            settleInterval,
                            renewalInterval,
                            renewalBudget,
                            drainDeadline))
                if (d.isZero() || d.isNegative())
                    throw new IllegalArgumentException("positive durations required");
            if (minPoll.compareTo(maxPoll) > 0)
                throw new IllegalArgumentException("invalid polling interval");
        }

        public static Settings pilot() {
            return new Settings(
                    32,
                    1,
                    Duration.ofMillis(10),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(90));
        }
    }

    record Pair(Binding binding, int partition) {}

    static final class LeaseEntry {
        final Pair pair;
        PartitionLease lease;
        boolean usable = true;
        long renewedAt = System.nanoTime();
        long retryUntil; // Local work budget after a qualified omission, never a DB deadline.
        long retryDue; // Preserve the original opportunity for complete lag/service measurement.

        LeaseEntry(Pair pair, PartitionLease lease) {
            this.pair = pair;
            this.lease = lease;
        }
    }

    enum Phase {
        WAITING,
        STARTED,
        ABANDONED,
        EXITED
    }

    final class Envelope implements Runnable {
        final Binding binding;
        final ConsumptionEngine.AcquiredClaim handle;
        Phase phase = Phase.WAITING;
        boolean cleanupAssigned, cleanupDone, wrapperExited, submissionDecided;
        long renewedAt = System.nanoTime();

        Envelope(Binding binding, ConsumptionEngine.AcquiredClaim handle) {
            this.binding = binding;
            this.handle = handle;
        }

        @Override
        public void run() {
            boolean start;
            synchronized (monitor) {
                start =
                        state == State.RUNNING
                                && envelopes.contains(this)
                                && phase == Phase.WAITING;
                if (start) phase = Phase.STARTED; // L4, atomic against L5; no Engine under monitor.
            }
            try {
                if (start) {
                    event(
                            binding,
                            ConsumptionObservation.Phase.ATTEMPT_STARTED,
                            Outcome.CONFIRMED,
                            1,
                            Duration.ZERO);
                    var attempt = binding.engine().execute(handle);
                    if (attempt.status() == ConsumptionEngine.Status.INCONSISTENT
                            || attempt.resolution()
                                    == com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions
                                            .Resolution.UNKNOWN) requestDrain();
                }
            } catch (RuntimeException | Error failure) {
                // An unqualified Engine exit cannot be converted into a release or re-execution.
                requestDrain();
            } finally {
                synchronized (monitor) {
                    if (start) phase = Phase.EXITED;
                    wrapperExited = true;
                }
                prune();
                signal();
            }
        }
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

    final Object monitor = new Object();
    final List<Binding> bindings;
    final WorkerCapacity capacity;
    final Map<ConsumptionId, Integer> limits;
    final Settings settings;
    final ConsumptionObservation observation;
    final Map<Pair, LeaseEntry> leases = new LinkedHashMap<>();
    final List<Envelope> envelopes = new ArrayList<>();
    final List<V3RenewalService.Call> renewalCalls = new ArrayList<>();
    final ConditionConsumptionWaiter discoveryWait = new ConditionConsumptionWaiter();
    final ConditionConsumptionWaiter consumptionWait = new ConditionConsumptionWaiter();
    final ConditionConsumptionWaiter maintenanceWait = new ConditionConsumptionWaiter();
    private final List<Runnable> stoppedCallbacks = new ArrayList<>();
    private final ThreadPoolExecutor executor;
    private final Runnable closeResources;
    private final V3RenewalService renewals;
    private final V3DiscoveryLoop discovery;
    private final V3ConsumptionLoop consumption;
    private final List<Thread> loopThreads = new ArrayList<>();
    private State state = State.STARTING;
    private boolean startCalled, initialized, discoveryExited, consumptionExited;
    private final CountDownLatch activated = new CountDownLatch(1);
    private final CountDownLatch startup;
    boolean renewalsAllowed = true;
    private int operations, submissions, cleanups;
    private long drainingSince;
    private boolean deadlineExceeded;

    public V3ConsumptionWorker(
            List<Binding> bindings,
            WorkerCapacity capacity,
            Map<ConsumptionId, Integer> limits,
            Settings settings,
            ConsumptionObservation observation,
            Runnable closeResources) {
        this(
                bindings,
                capacity,
                limits,
                settings,
                observation,
                closeResources,
                new ThreadPoolExecutor(
                        capacity.workerLimit(),
                        capacity.workerLimit(),
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        namedFactory("v3-attempt"),
                        new ThreadPoolExecutor.AbortPolicy()));
    }

    V3ConsumptionWorker(
            List<Binding> bindings,
            WorkerCapacity capacity,
            Map<ConsumptionId, Integer> limits,
            Settings settings,
            ConsumptionObservation observation,
            Runnable closeResources,
            ThreadPoolExecutor executor) {
        this.bindings = List.copyOf(bindings);
        this.capacity = Objects.requireNonNull(capacity);
        this.limits = Map.copyOf(limits);
        this.settings = Objects.requireNonNull(settings);
        this.observation = Objects.requireNonNull(observation);
        this.closeResources = Objects.requireNonNull(closeResources);
        this.executor = Objects.requireNonNull(executor);
        if (bindings.isEmpty()
                || bindings.stream().map(b -> b.definition().id()).distinct().count()
                        != bindings.size()
                || !limits.keySet()
                        .equals(
                                new HashSet<>(
                                        bindings.stream().map(b -> b.definition().id()).toList()))
                || limits.values().stream().anyMatch(n -> n < 1))
            throw new IllegalArgumentException("invalid worker catalog");
        this.startup =
                new CountDownLatch(
                        3
                                + 3
                                        * (int)
                                                this.bindings.stream()
                                                        .map(b -> b.definition().authority())
                                                        .distinct()
                                                        .count());
        this.discovery = new V3DiscoveryLoop(this);
        this.consumption = new V3ConsumptionLoop(this);
        this.renewals = new V3RenewalService(this);
    }

    static ThreadFactory namedFactory(String prefix) {
        return Thread.ofPlatform().name(prefix + "-", 0).daemon(false).factory();
    }

    public void start() {
        synchronized (monitor) {
            if (startCalled) throw new IllegalStateException("Worker is single-use");
            startCalled = true;
        }
        try {
            executor.prestartAllCoreThreads();
            var d =
                    namedFactory("v3-discovery")
                            .newThread(() -> loop(discovery::runOnePass, discoveryWait, true));
            var c =
                    namedFactory("v3-consumption")
                            .newThread(() -> loop(consumption::runOnePass, consumptionWait, false));
            loopThreads.add(d);
            loopThreads.add(c);
            renewals.start();
            d.start();
            c.start();
            namedFactory("v3-maintenance")
                    .newThread(
                            () -> {
                                try {
                                    awaitActivation();
                                    maintain();
                                } catch (InterruptedException failure) {
                                    requestDrain();
                                    Thread.currentThread().interrupt();
                                }
                            })
                    .start();
            if (!startup.await(30, TimeUnit.SECONDS)) requestDrain();
            synchronized (monitor) {
                initialized = true;
                if (state == State.STARTING) state = State.RUNNING;
            }
        } catch (InterruptedException failure) {
            requestDrain();
            Thread.currentThread().interrupt();
        } catch (RuntimeException | Error failure) {
            requestDrain();
            throw failure;
        } finally {
            activated.countDown();
            signal();
        }
    }

    /** Startup ACK and wait occur outside the lifecycle monitor, never during a SQL unit. */
    void awaitActivation() throws InterruptedException {
        startup.countDown();
        activated.await();
    }

    public boolean isRunning() {
        synchronized (monitor) {
            return startCalled && state != State.STOPPED;
        }
    }

    boolean running() {
        synchronized (monitor) {
            return state == State.RUNNING;
        }
    }

    boolean beginOperation() {
        synchronized (monitor) {
            if (state != State.RUNNING) return false;
            operations++;
            return true; // L1 encompasses post-acquisition ownership transfer.
        }
    }

    void endOperation(Throwable failure) {
        boolean resolved =
                failure == null
                        || (failure
                                        instanceof
                                        com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions
                                                                .Failure
                                                        tx
                                && tx.resolution()
                                        != com.kartaguez.pocoma.port.transaction.v3
                                                .AttemptTransactions.Resolution.UNKNOWN);
        if (!resolved) {
            // L1 remains outstanding after an unqualified local return. These ports expose
            // no physical witness for the whole opportunity; neither time nor a released
            // business ticket permits closing its pools. Only Worker termination drops it.
            requestDrain();
            try {
                System.getLogger(V3ConsumptionWorker.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Unresolved Worker opportunity retained: type={0}, causeType={1}",
                                failure.getClass().getName(),
                                failure.getCause() == null
                                        ? "none"
                                        : failure.getCause().getClass().getName());
            } catch (RuntimeException | Error ignored) {
                // Diagnostic types only; logging cannot qualify or release the operation.
            }
            return;
        }
        synchronized (monitor) {
            operations--;
        }
        maintenanceWait.signal();
    }

    PartitionLease lease(Binding binding, int partition) {
        var pair = new Pair(binding, partition);
        synchronized (monitor) {
            var existing = leases.get(pair);
            if (existing != null) return existing.usable ? existing.lease : null;
        }
        var acquired = binding.engine().acquirePartition(partition);
        synchronized (monitor) {
            if (state != State.RUNNING) return null;
            if (acquired.isPresent() && !leases.containsKey(pair))
                leases.put(pair, new LeaseEntry(pair, acquired.get()));
            var current = leases.get(pair);
            return current != null && current.usable ? current.lease : null;
        }
    }

    void suspend(Binding binding, PartitionLease lease) {
        boolean changed = false;
        synchronized (monitor) {
            var current = leases.get(new Pair(binding, lease.partitionId()));
            if (current != null
                    && current.usable
                    && current.lease.ownerToken().equals(lease.ownerToken())) {
                current.usable = false;
                changed = true;
            }
        }
        if (changed)
            event(
                    binding,
                    ConsumptionObservation.Phase.LEASE_SUSPEND,
                    Outcome.CONFIRMED,
                    1,
                    Duration.ZERO);
    }

    boolean dispatchAvailable() {
        return !executor.isShutdown() && executor.getActiveCount() < executor.getMaximumPoolSize();
    }

    void dispatch(Binding binding, List<ConsumptionEngine.AcquiredClaim> handles) {
        int transferred = 0;
        try {
            for (var handle : handles) {
                prune();
                var envelope = new Envelope(binding, handle);
                boolean cleanup;
                synchronized (monitor) {
                    envelopes.add(envelope); // L2, including late acquisition after L5.
                    transferred++;
                    cleanup = state != State.RUNNING && markAbandoned(envelope);
                }
                if (cleanup) cleanup(envelope);
                submit(envelope);
            }
        } finally {
            // Keep ownership of every returned handle even when local envelope preparation fails.
            for (int i = transferred; i < handles.size(); i++) {
                // No further envelope allocation is needed to clean an untransferred suffix.
                // L1 still owns this bounded list; the unique loop caller owns each cleanup.
                synchronized (monitor) {
                    cleanups++;
                }
                boolean done = false;
                try {
                    binding.engine().abandon(handles.get(i));
                    done = true;
                } catch (RuntimeException | Error failure) {
                    requestDrain();
                } finally {
                    synchronized (monitor) {
                        if (done) cleanups--;
                    }
                }
            }
            prune();
        }
    }

    private void submit(Envelope envelope) {
        boolean admitted;
        synchronized (monitor) {
            admitted = state == State.RUNNING && envelope.phase == Phase.WAITING;
            if (admitted) submissions++; // L3; execute remains outside the monitor.
            else {
                envelope.wrapperExited = true;
                envelope.submissionDecided = true;
            }
        }
        if (!admitted) {
            abandonBeforeStart(envelope);
            return;
        }
        try {
            executor.execute(envelope);
        } catch (RejectedExecutionException rejected) {
            synchronized (monitor) {
                envelope.wrapperExited = true;
            }
            event(
                    envelope.binding,
                    executor.isShutdown()
                            ? ConsumptionObservation.Phase.DISPATCH_STOPPED
                            : executor.getActiveCount() >= executor.getMaximumPoolSize()
                                    ? ConsumptionObservation.Phase.DISPATCH_SATURATED
                                    : ConsumptionObservation.Phase.DISPATCH_REJECTED,
                    Outcome.REJECTED,
                    1,
                    Duration.ZERO);
            abandonBeforeStart(envelope);
        } finally {
            synchronized (monitor) {
                envelope.submissionDecided = true;
                submissions--;
            }
            prune();
            maintenanceWait.signal();
        }
    }

    private boolean markAbandoned(Envelope envelope) {
        if (envelope.phase != Phase.WAITING) return false;
        envelope.phase = Phase.ABANDONED;
        envelope.cleanupAssigned = true;
        cleanups++;
        return true;
    }

    void abandonBeforeStart(Envelope envelope) {
        boolean assigned;
        synchronized (monitor) {
            assigned = markAbandoned(envelope);
        }
        if (assigned) cleanup(envelope);
    }

    private void cleanup(Envelope envelope) {
        boolean done = false;
        try {
            envelope.binding.engine().abandon(envelope.handle);
            done = true;
        } catch (RuntimeException | Error fault) {
            requestDrain();
        } finally {
            synchronized (monitor) {
                if (done) {
                    envelope.cleanupDone = true;
                    cleanups--;
                }
                // Failed cleanup remains explicitly unresolved; no second abandon or fake release.
            }
            prune();
            signal();
        }
    }

    public void requestDrain() {
        requestDrain(null);
    }

    public void requestDrain(Runnable stopped) {
        List<Envelope> cleanup = new ArrayList<>();
        boolean alreadyStopped;
        synchronized (monitor) {
            alreadyStopped = state == State.STOPPED;
            if (!alreadyStopped && stopped != null) stoppedCallbacks.add(stopped);
            if (state == State.STARTING || state == State.RUNNING) {
                state = State.DRAINING;
                drainingSince = System.nanoTime(); // L5 closes every future L4.
                for (var envelope : envelopes) if (markAbandoned(envelope)) cleanup.add(envelope);
            }
        }
        if (alreadyStopped) {
            if (stopped != null) stopped.run();
            return;
        }
        activated.countDown();
        executor.shutdown();
        signal(); // No SQL wait before making shutdown effective.
        cleanup.forEach(this::cleanup); // L6, sole winners, outside monitor.
    }

    void prune() {
        List<Envelope> snapshot;
        synchronized (monitor) {
            snapshot = List.copyOf(envelopes);
        }
        var released =
                snapshot.stream()
                        .filter(e -> !e.binding.engine().retainsCapacity(e.handle))
                        .toList();
        synchronized (monitor) {
            for (var e : released)
                if (e.wrapperExited
                        && e.submissionDecided
                        && (e.phase == Phase.EXITED
                                || (e.phase == Phase.ABANDONED && e.cleanupDone)))
                    envelopes.remove(e);
        }
    }

    public Snapshot snapshot() {
        synchronized (monitor) {
            return new Snapshot(
                    state,
                    envelopes.size(),
                    (int) envelopes.stream().filter(e -> e.phase == Phase.STARTED).count(),
                    (int) envelopes.stream().filter(e -> e.wrapperExited).count(),
                    operations,
                    submissions,
                    cleanups,
                    renewalCalls.size(),
                    (int) renewalCalls.stream().filter(c -> c.unknown).count(),
                    deadlineExceeded);
        }
    }

    public record RenewalSnapshot(
            ConsumptionDefinition.Authority authority, int lane, boolean unknown) {}

    public List<RenewalSnapshot> renewalSnapshot() {
        synchronized (monitor) {
            return renewalCalls.stream()
                    .map(c -> new RenewalSnapshot(c.authority, c.lane, c.unknown))
                    .toList();
        }
    }

    public Map<ConsumptionDefinition.Authority, Long> localLeases() {
        synchronized (monitor) {
            var counts =
                    new EnumMap<ConsumptionDefinition.Authority, Long>(
                            ConsumptionDefinition.Authority.class);
            for (var e : leases.values())
                if (e.usable)
                    counts.merge(e.pair.binding().definition().authority(), 1L, Long::sum);
            return Map.copyOf(counts);
        }
    }

    void event(
            Binding binding,
            ConsumptionObservation.Phase phase,
            Outcome outcome,
            long amount,
            Duration duration) {
        ConsumptionObservation.safely(
                observation, binding.definition().id(), phase, outcome, amount, duration);
    }

    void signal() {
        discoveryWait.signal();
        consumptionWait.signal();
        maintenanceWait.signal();
        renewals.signal();
    }

    private void loop(
            java.util.function.BooleanSupplier pass,
            ConsumptionWaiter waiter,
            boolean isDiscovery) {
        Duration backoff = settings.minPoll();
        int tour =
                bindings.size()
                        * bindings.stream()
                                .mapToInt(b -> b.partitions().size())
                                .max()
                                .orElseThrow();
        int remaining = tour;
        try {
            awaitActivation();
            while (running()) {
                Duration delay = settings.minPoll();
                long cycleBegan = System.nanoTime();
                boolean returned = false;
                try {
                    boolean progressed = pass.getAsBoolean();
                    returned = true;
                    if (progressed) {
                        backoff = settings.minPoll();
                        remaining = tour;
                    } else if (--remaining == 0) {
                        delay = backoff;
                        remaining = tour;
                        backoff =
                                backoff.multipliedBy(2).compareTo(settings.maxPoll()) > 0
                                        ? settings.maxPoll()
                                        : backoff.multipliedBy(2);
                    }
                } catch (RuntimeException fault) {
                    delay = settings.errorPoll();
                } finally {
                    event(
                            bindings.getFirst(),
                            isDiscovery
                                    ? ConsumptionObservation.Phase.DISCOVERY_CYCLE
                                    : ConsumptionObservation.Phase.CONSUMPTION_CYCLE,
                            returned ? Outcome.CONFIRMED : Outcome.UNKNOWN,
                            1,
                            Duration.ofNanos(System.nanoTime() - cycleBegan));
                }
                waiter.await(delay);
            }
        } catch (InterruptedException | Error failure) {
            requestDrain();
            Thread.currentThread().interrupt();
        } finally {
            synchronized (monitor) {
                if (isDiscovery) discoveryExited = true;
                else consumptionExited = true;
            }
            maintenanceWait.signal();
        }
    }

    boolean recoveryUncertain() {
        return bindings.stream().anyMatch(b -> b.engine().hasUnresolvedRecovery());
    }

    private void maintain() {
        try {
            while (true) {
                renewals.settleOnce();
                prune();
                renewals.checkDeadlines();
                boolean ready, newlyExceeded = false;
                int used = capacity.snapshot().worker();
                boolean unresolvedRecovery = recoveryUncertain();
                if (unresolvedRecovery) requestDrain();
                synchronized (monitor) {
                    if (state == State.DRAINING
                            && !deadlineExceeded
                            && System.nanoTime() - drainingSince
                                    >= settings.drainDeadline().toNanos()) {
                        deadlineExceeded = true;
                        newlyExceeded = true;
                    }
                    ready =
                            state == State.DRAINING
                                    && initialized
                                    && discoveryExited
                                    && consumptionExited
                                    && operations == 0
                                    && submissions == 0
                                    && cleanups == 0
                                    && envelopes.isEmpty()
                                    && used == 0
                                    && renewalCalls.isEmpty()
                                    && !unresolvedRecovery
                                    && executor.isTerminated();
                    if (ready) renewalsAllowed = false;
                }
                if (newlyExceeded)
                    event(
                            bindings.getFirst(),
                            ConsumptionObservation.Phase.DRAIN,
                            Outcome.UNKNOWN,
                            1,
                            Duration.ofNanos(System.nanoTime() - drainingSince));
                if (ready) {
                    renewals.stopAndJoin();
                    for (var t : loopThreads) t.join();
                    closeResources.run();
                    List<Runnable> callbacks;
                    synchronized (monitor) {
                        state = State.STOPPED;
                        callbacks = List.copyOf(stoppedCallbacks);
                        stoppedCallbacks.clear();
                    }
                    event(
                            bindings.getFirst(),
                            ConsumptionObservation.Phase.DRAIN,
                            Outcome.COMMITTED,
                            1,
                            Duration.ofNanos(System.nanoTime() - drainingSince));
                    for (var callback : callbacks) {
                        try {
                            callback.run();
                        } catch (RuntimeException | Error ignored) {
                        }
                    }
                    return;
                }
                maintenanceWait.await(settings.settleInterval());
            }
        } catch (InterruptedException | RuntimeException | Error failure) {
            requestDrain(); // Unsuccessful maintenance cannot announce STOPPED or release capacity.
        }
    }
}
