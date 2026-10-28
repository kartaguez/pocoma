package com.kartaguez.pocoma.engine.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.function.*;

class DiscoveryEngineTest {
    static UUID u(long n) {
        return new UUID(0, n);
    }

    static DiscoveryEngine.Settings settings(long cap) {
        return new DiscoveryEngine.Settings(
                128, 32, 32, cap, Duration.ofMillis(500), Duration.ofSeconds(2));
    }

    static final class Harness
            implements AttemptTransactions,
                    PartitionOwnershipStore,
                    DiscoveryAdmissionStore,
                    NonBurnableReferences,
                    BurnableReferences {
        final ConsumptionDefinition definition;
        final PartitionLease lease;
        final Set<UUID> slots = new HashSet<>(), skipped = new HashSet<>();
        final List<UUID> protectedIds = new ArrayList<>();
        List<NonBurnableReferences.Reference> events = List.of();
        List<UUID> pending = List.of();
        long k, stock;
        boolean unknown, owned = true;

        Harness(ConsumableFamily family) {
            definition =
                    new ConsumptionDefinition(
                            new ConsumptionId(u(100)),
                            family,
                            family == ConsumableFamily.COMMAND
                                    ? ConsumptionDefinition.Authority.WRITE
                                    : ConsumptionDefinition.Authority.READ);
            lease = new PartitionLease(definition.id(), 0, u(101), Instant.MAX);
        }

        DiscoveryEngine engine(long cap) {
            return family() == ConsumableFamily.COMMAND
                    ? new DiscoveryEngine(
                            definition, this, this, this, settings(cap), (BurnableReferences) this)
                    : new DiscoveryEngine(
                            definition, this, this, this, settings(cap), this, this, r -> true);
        }

        public <T> T read(Function<RecoveryReadContext, T> f) {
            return f.apply(() -> {});
        }

        public <T> T write(Supplier<T> f) {
            T result = f.get();
            if (unknown)
                throw new AttemptTransactions.Failure(
                        Resolution.UNKNOWN, new IllegalStateException("lost ACK"));
            return result;
        }

        public void poison(Throwable cause) {
            throw new AssertionError("unused");
        }

        public void requirePublicationClosed() {
            throw new AssertionError("Discovery never closes publication");
        }

        public Optional<PartitionLease> acquire(
                ConsumptionDefinition d, int p, UUID t, Duration duration) {
            throw new AssertionError();
        }

        public List<PartitionLease> renew(
                ConsumptionDefinition d, List<PartitionLease> l, Duration duration) {
            throw new AssertionError();
        }

        public Optional<PartitionLease> protectAcquisition(
                ConsumptionDefinition d, PartitionLease l) {
            return owned ? Optional.of(l) : Optional.empty();
        }

        public void bound(Duration lock, Duration statement) {}

        public long checkpoint(ConsumptionId id, int p, RecoveryReadContext r) {
            return k;
        }

        public long currentCheckpoint(PartitionLease l) {
            return k;
        }

        public long liveSlots(PartitionLease l) {
            return stock;
        }

        public boolean contains(ConsumptionDefinition d, int p, UUID id) {
            return slots.contains(id);
        }

        public void insertInitial(ConsumptionDefinition d, int p, UUID id) {
            assertTrue(slots.add(id));
        }

        public void advanceCheckpoint(PartitionLease l, long expected, long next) {
            assertEquals(k, expected);
            k = next;
        }

        public ConsumableFamily family() {
            return definition.family();
        }

        public NonBurnableReferences.Window window(long after, int max, RecoveryReadContext r) {
            return new NonBurnableReferences.Window(after, after + events.size(), events);
        }

        public Optional<UUID> upperPending(RecoveryReadContext r) {
            return pending.stream().reduce((a, b) -> b);
        }

        public List<UUID> pendingPage(
                Optional<UUID> after, UUID upper, int size, RecoveryReadContext r) {
            return pending;
        }

        public boolean tryProtectPending(UUID id) {
            protectedIds.add(id);
            return !skipped.contains(id);
        }

        void fourEvents() {
            events =
                    List.of(
                            new NonBurnableReferences.Reference(1, u(2), "POT"),
                            new NonBurnableReferences.Reference(2, u(5), "POT"),
                            new NonBurnableReferences.Reference(3, u(3), "POT"),
                            new NonBurnableReferences.Reference(4, u(8), "POT"));
        }
    }

    @Test
    void coveredPrefixStopsBeforeFirstUnadmittedReference() {
        var h = new Harness(ConsumableFamily.POT_EVENT);
        h.fourEvents();
        var result = h.engine(1).discoverEvents(h.lease);
        assertEquals(2, result.checkpoint().orElseThrow());
        assertEquals(Set.of(u(2)), h.slots);
        assertEquals(DiscoveryEngine.Status.CAPACITY, result.status());
    }

    @Test
    void zeroLocalReferencesAdvanceGlobally() {
        var h = new Harness(ConsumableFamily.POT_EVENT);
        h.stock = 1;
        h.events = List.of(new NonBurnableReferences.Reference(1, u(5), "POT"));
        assertEquals(1, h.engine(1).discoverEvents(h.lease).checkpoint().orElseThrow());
        assertTrue(h.slots.isEmpty());
    }

    @Test
    void quarantinedPresenceIsNotNewWork() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3), u(9));
        h.slots.add(u(3));
        var result = h.engine(1).discoverRequests(h.lease, DiscoveryEngine.RequestWalk.fresh());
        assertEquals(2, result.advanced());
        assertEquals(1, result.committedAdmissions());
        assertEquals(Set.of(u(3), u(9)), h.slots);
    }

    @Test
    void filteredPageIsNotEndOfTour() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3));
        h.skipped.add(u(3));
        var result =
                h.engine(1)
                        .discoverRequests(
                                h.lease,
                                new DiscoveryEngine.RequestWalk(
                                        Optional.empty(), Optional.of(u(19))));
        assertFalse(result.tourFinished());
        assertEquals(Optional.of(u(3)), result.next().afterExclusive());
        assertEquals(1, result.examined());
        assertEquals(1, result.advanced());
        assertEquals(0, result.committedAdmissions());
    }

    @Test
    void capacityPreservesUnexaminedSuffix() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3), u(9), u(12), u(15), u(19));
        h.skipped.add(u(9));
        h.slots.add(u(12));
        var result = h.engine(1).discoverRequests(h.lease, DiscoveryEngine.RequestWalk.fresh());
        assertEquals(4, result.examined());
        assertEquals(3, result.advanced());
        assertEquals(1, result.committedAdmissions());
        assertEquals(Optional.of(u(12)), result.next().afterExclusive());
        assertEquals(Optional.of(u(19)), result.next().upperInclusive());
        assertEquals(List.of(u(3), u(9), u(12), u(15)), h.protectedIds);
        var full = new Harness(ConsumableFamily.COMMAND);
        full.stock = 1;
        full.pending = h.pending;
        var walk = DiscoveryEngine.RequestWalk.fresh();
        var stopped = full.engine(1).discoverRequests(full.lease, walk);
        assertEquals(walk, stopped.next());
        assertEquals(0, stopped.examined());
        assertTrue(full.protectedIds.isEmpty());
    }

    @Test
    void walkUsesUnsignedUuidOrder() {
        UUID low = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                high = UUID.fromString("80000000-0000-0000-0000-000000000000");
        assertDoesNotThrow(
                () -> new DiscoveryEngine.RequestWalk(Optional.of(low), Optional.of(high)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscoveryEngine.RequestWalk(Optional.of(high), Optional.of(low)));
    }

    @Test
    void unknownTransactionReturnsNoProgress() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3));
        h.unknown = true;
        assertThrows(
                AttemptTransactions.Failure.class,
                () -> h.engine(1).discoverRequests(h.lease, DiscoveryEngine.RequestWalk.fresh()));
    }

    @Test
    void invalidSourceOrWindowRefusedBeforeAdmission() {
        var h = new Harness(ConsumableFamily.POT_EVENT);
        h.events = List.of(new NonBurnableReferences.Reference(2, u(2), "POT"));
        assertThrows(IllegalStateException.class, () -> h.engine(1).discoverEvents(h.lease));
        assertTrue(h.slots.isEmpty());
        assertEquals(0, h.k);
        assertThrows(
                IllegalStateException.class,
                () -> h.engine(1).discoverRequests(h.lease, DiscoveryEngine.RequestWalk.fresh()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscoveryEngine.Settings(0, 1, 1, 1, Duration.ZERO, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscoveryEngine.RequestWalk(Optional.of(u(1)), Optional.empty()));
        for (int[] bounds :
                List.of(
                        new int[] {-1, 1, 1, 1},
                        new int[] {1, 0, 1, 1},
                        new int[] {1, 1, 0, 1},
                        new int[] {1, 1, 1, 0}))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new DiscoveryEngine.Settings(
                                    bounds[0],
                                    bounds[1],
                                    bounds[2],
                                    bounds[3],
                                    Duration.ofMillis(1),
                                    Duration.ofMillis(2)));
        for (var duration :
                List.of(
                        Duration.ZERO,
                        Duration.ofNanos(1),
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(Long.MAX_VALUE)))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new DiscoveryEngine.Settings(
                                    1, 1, 1, 1, duration, Duration.ofSeconds(2)));
        assertThrows(
                NullPointerException.class,
                () -> new DiscoveryEngine.Settings(1, 1, 1, 1, null, Duration.ofSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoveryEngine.Settings(
                                1, 1, 1, 1, Duration.ofSeconds(3), Duration.ofSeconds(2)));
        assertThrows(
                NullPointerException.class,
                () -> new DiscoveryEngine.RequestWalk(null, Optional.empty()));
        assertThrows(
                NullPointerException.class, () -> new NonBurnableReferences.Window(0, 1, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NonBurnableReferences.Window(Long.MAX_VALUE, 0, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new NonBurnableReferences.Reference(1, null, "POT"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new NonBurnableReferences.Reference(1, u(2), " "));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        h.engine(1)
                                .discoverEvents(
                                        new PartitionLease(
                                                new ConsumptionId(u(200)),
                                                0,
                                                u(101),
                                                Instant.MAX)));
        var wrong = new Harness(ConsumableFamily.COMMAND);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoveryEngine(
                                h.definition,
                                h,
                                h,
                                h,
                                settings(1),
                                h,
                                (NonBurnableReferences) wrong,
                                r -> true));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoveryEngine(
                                h.definition, h, h, h, settings(1), (BurnableReferences) wrong));
        assertThrows(
                IllegalStateException.class, () -> wrong.engine(1).discoverEvents(wrong.lease));
        wrong.pending = List.of(u(9), u(3));
        assertThrows(
                IllegalStateException.class,
                () ->
                        wrong.engine(1)
                                .discoverRequests(
                                        wrong.lease, DiscoveryEngine.RequestWalk.fresh()));
        assertTrue(wrong.slots.isEmpty());
        h.owned = false;
        h.events = List.of();
        assertTrue(h.engine(1).discoverEvents(h.lease).checkpoint().isEmpty());
    }
}
