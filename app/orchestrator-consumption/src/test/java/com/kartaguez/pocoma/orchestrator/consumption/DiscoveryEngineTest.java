package com.kartaguez.pocoma.orchestrator.consumption;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports;
import com.kartaguez.pocoma.contract.consumption.spi.BurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

class DiscoveryEngineTest {
    static UUID u(long n) {
        return new UUID(0, n);
    }

    static DiscoverySettings settings(long cap) {
        return new DiscoverySettings(
                128, 32, 32, cap, Duration.ofMillis(500), Duration.ofSeconds(2));
    }

    static class Harness
            implements AttemptTransactions,
                    PartitionOwnershipStore,
                    DiscoveryAdmissionStore,
                    NonBurnableReferences {
        final ConsumptionDefinition definition;
        final PartitionLease lease;
        final Set<UUID> slots = new HashSet<>(),
                skipped = new HashSet<>(),
                terminal = new HashSet<>();
        final List<UUID> protectedIds = new ArrayList<>();
        List<NonBurnableReferences.Reference> events = List.of();
        List<BurnableReferences.Reference> requestWindow = null;
        List<UUID> pending = List.of();
        long k, stock, actualK = -1;
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

        BurnableReferences burnableSource() {
            return new BurnableReferences() {
                @Override
                public ConsumableFamily family() {
                    return definition.family();
                }

                @Override
                public Window window(long after, int max, RecoveryReadContext r) {
                    if (requestWindow != null) {
                        return new Window(after, after + requestWindow.size(), requestWindow);
                    }
                    var refs = new ArrayList<Reference>();
                    for (int i = (int) after;
                            i < pending.size() && refs.size() < max;
                            i++) {
                        refs.add(new Reference(i + 1L, pending.get(i)));
                    }
                    return new Window(after, after + refs.size(), refs);
                }

                @Override
                public boolean tryProtectPending(UUID id) {
                    protectedIds.add(id);
                    return !skipped.contains(id) && !terminal.contains(id);
                }

                @Override
                public boolean isTerminal(UUID id) {
                    return terminal.contains(id);
                }
            };
        }

        DiscoveryEngine engine(long cap) {
            return family() == ConsumableFamily.COMMAND
                    ? new DiscoveryEngine(
                            definition, this, this, this, settings(cap), burnableSource())
                    : new DiscoveryEngine(
                            definition, this, this, this, settings(cap), this, this, r -> true);
        }

        public <T> T read(
                Function<RecoveryReadContext, T> f,
                com.kartaguez.pocoma.port.transaction.AttemptTransactions.Guard guard) {
            guard.requireValid();
            return f.apply(() -> {});
        }

        public <T> T write(
                Supplier<T> f,
                com.kartaguez.pocoma.port.transaction.AttemptTransactions.Guard guard) {
            guard.requireValid();
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
            return actualK >= 0 ? actualK : k;
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

        public java.util.Optional<
                        com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences
                                .Reference>
                find(
                        java.util.UUID eventId,
                        com.kartaguez.pocoma.port.transaction.RecoveryReadContext context) {
            throw new AssertionError("This discovery-only source does not implement recovery");
        }

        public NonBurnableReferences.Window window(long after, int max, RecoveryReadContext r) {
            return new NonBurnableReferences.Window(after, after + events.size(), events);
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
        assertEquals(DiscoveryReports.Status.CAPACITY, result.status());
    }

    @Test
    void zeroLocalReferencesAdvanceGlobally() {
        var h = new Harness(ConsumableFamily.POT_EVENT);
        h.stock = 1;
        h.events = List.of(new NonBurnableReferences.Reference(1, u(5), "POT"));
        assertEquals(1, h.engine(1).discoverEvents(h.lease).checkpoint().orElseThrow());
        assertTrue(h.slots.isEmpty());
        // Empty window short-circuits redundant trailing fence checks while still validating lease & checkpoint.
        h.events = List.of();
        h.k = 1;
        var emptyEvent = h.engine(1).discoverEvents(h.lease);
        assertEquals(DiscoveryReports.Status.COMMITTED, emptyEvent.status());
        assertEquals(1L, emptyEvent.checkpoint().orElseThrow());
        var reqH = new Harness(ConsumableFamily.COMMAND);
        reqH.pending = List.of();
        var emptyReq = reqH.engine(1).discoverRequests(reqH.lease);
        assertEquals(DiscoveryReports.Status.COMMITTED, emptyReq.status());
        assertEquals(0L, emptyReq.checkpoint().orElseThrow());
    }

    private record ExpectedDiscovery(
            DiscoveryReports.Status status,
            int examined,
            int advanced,
            int committed,
            long checkpoint) {}

    private static void assertDiscoveryReport(
            DiscoveryReports.RequestProgress report, ExpectedDiscovery expected) {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> {
                    if (expected.status() != null) {
                        assertEquals(expected.status(), report.status());
                    }
                },
                () -> assertEquals(expected.examined(), report.examined()),
                () -> assertEquals(expected.advanced(), report.advanced()),
                () -> assertEquals(expected.committed(), report.committedAdmissions()),
                () -> assertEquals(expected.checkpoint(), report.checkpoint().orElseThrow()));
    }

    @Test
    void quarantinedPresenceIsNotNewWork() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3), u(9));
        h.slots.add(u(3));
        var result = h.engine(1).discoverRequests(h.lease);
        assertDiscoveryReport(result, new ExpectedDiscovery(null, 2, 2, 1, 2L));
        assertEquals(Set.of(u(3), u(9)), h.slots);
    }

    @Test
    void lockedPendingHoldsBackCheckpointWhileTerminalAdvances() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3), u(9), u(12));
        h.terminal.add(u(3));
        h.skipped.add(u(9));
        var first = h.engine(2).discoverRequests(h.lease);
        assertDiscoveryReport(
                first, new ExpectedDiscovery(DiscoveryReports.Status.COMMITTED, 2, 1, 0, 1L));
        assertEquals(1L, h.k);
        assertTrue(h.slots.isEmpty());

        // Once the lock on u(9) is released, the next pass resumes from checkpoint 1 and admits u(9) and u(12)
        h.skipped.remove(u(9));
        var second = h.engine(2).discoverRequests(h.lease);
        assertDiscoveryReport(
                second, new ExpectedDiscovery(DiscoveryReports.Status.COMMITTED, 2, 2, 2, 3L));
        assertEquals(3L, h.k);
        assertEquals(Set.of(u(9), u(12)), h.slots);
    }

    @Test
    void capacityPreservesUnexaminedSuffix() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3), u(9), u(12), u(15), u(19));
        h.terminal.add(u(9));
        h.slots.add(u(12));
        var result = h.engine(1).discoverRequests(h.lease);
        assertDiscoveryReport(
                result, new ExpectedDiscovery(DiscoveryReports.Status.CAPACITY, 4, 3, 1, 3L));
        assertEquals(List.of(u(3), u(9), u(12), u(15)), h.protectedIds);
        var full = new Harness(ConsumableFamily.COMMAND);
        full.stock = 1;
        full.pending = h.pending;
        var stopped = full.engine(1).discoverRequests(full.lease);
        assertDiscoveryReport(
                stopped, new ExpectedDiscovery(DiscoveryReports.Status.CAPACITY, 0, 0, 0, 0L));
        assertTrue(full.protectedIds.isEmpty());
    }

    @Test
    void staleCheckpointAndNotOwnerOnRequests() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3));
        h.actualK = 5L;
        var stale = h.engine(1).discoverRequests(h.lease);
        assertEquals(DiscoveryReports.Status.STALE_CHECKPOINT, stale.status());
        assertEquals(5L, stale.checkpoint().orElseThrow());
        h.owned = false;
        var notOwner = h.engine(1).discoverRequests(h.lease);
        assertEquals(DiscoveryReports.Status.BUSY_OR_NOT_OWNER, notOwner.status());
        assertTrue(notOwner.checkpoint().isEmpty());
    }

    @Test
    void unknownTransactionReturnsNoProgress() {
        var h = new Harness(ConsumableFamily.COMMAND);
        h.pending = List.of(u(3));
        h.unknown = true;
        assertThrows(
                AttemptTransactions.Failure.class,
                () -> h.engine(1).discoverRequests(h.lease));
    }

    private static void assertInvalidDiscoverySettingsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DiscoverySettings(0, 1, 1, 1, Duration.ZERO, Duration.ZERO));
        for (int[] bounds :
                List.of(
                        new int[] {-1, 1, 1, 1},
                        new int[] {1, 0, 1, 1},
                        new int[] {1, 1, 0, 1},
                        new int[] {1, 1, 1, 0})) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new DiscoverySettings(
                                    bounds[0],
                                    bounds[1],
                                    bounds[2],
                                    bounds[3],
                                    Duration.ofMillis(1),
                                    Duration.ofMillis(2)));
        }
        for (var duration :
                List.of(
                        Duration.ZERO,
                        Duration.ofNanos(1),
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new DiscoverySettings(1, 1, 1, 1, duration, Duration.ofSeconds(2)));
        }
        assertThrows(
                NullPointerException.class,
                () -> new DiscoverySettings(1, 1, 1, 1, null, Duration.ofSeconds(2)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoverySettings(
                                1, 1, 1, 1, Duration.ofSeconds(3), Duration.ofSeconds(2)));
    }

    private static void assertInvalidWindowsAndReferencesRejected() {
        org.junit.jupiter.api.Assertions.assertAll(
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new NonBurnableReferences.Window(0, 1, null)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new NonBurnableReferences.Window(
                                                Long.MAX_VALUE, 0, List.of())),
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new NonBurnableReferences.Reference(1, null, "POT")),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new NonBurnableReferences.Reference(1, u(2), " ")),
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new BurnableReferences.Window(0, 1, null)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new BurnableReferences.Window(Long.MAX_VALUE, 0, List.of())),
                () ->
                        assertThrows(
                                NullPointerException.class,
                                () -> new BurnableReferences.Reference(1, null)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> new BurnableReferences.Reference(0, u(2))));
    }

    @Test
    void invalidSourceOrWindowRefusedBeforeAdmission() {
        var h = new Harness(ConsumableFamily.POT_EVENT);
        h.events = List.of(new NonBurnableReferences.Reference(2, u(2), "POT"));
        assertThrows(IllegalStateException.class, () -> h.engine(1).discoverEvents(h.lease));
        assertTrue(h.slots.isEmpty());
        assertEquals(0, h.k);
        assertThrows(IllegalStateException.class, () -> h.engine(1).discoverRequests(h.lease));
        assertInvalidDiscoverySettingsRejected();
        assertInvalidWindowsAndReferencesRejected();
        var expiredLease = new PartitionLease(new ConsumptionId(u(200)), 0, u(101), Instant.MAX);
        var wrong = new Harness(ConsumableFamily.COMMAND);
        wrong.requestWindow = List.of(new BurnableReferences.Reference(2, u(9)));
        org.junit.jupiter.api.Assertions.assertAll(
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () -> h.engine(1).discoverEvents(expiredLease)),
                () ->
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
                                                r -> true)),
                () ->
                        assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new DiscoveryEngine(
                                                h.definition,
                                                h,
                                                h,
                                                h,
                                                settings(1),
                                                wrong.burnableSource())),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> wrong.engine(1).discoverEvents(wrong.lease)),
                () ->
                        assertThrows(
                                IllegalStateException.class,
                                () -> wrong.engine(1).discoverRequests(wrong.lease)));
        assertTrue(wrong.slots.isEmpty());
        h.owned = false;
        h.events = List.of();
        assertTrue(h.engine(1).discoverEvents(h.lease).checkpoint().isEmpty());
    }

    @Test
    void b2_checkpointCacheHitsSameOwnerAndInvalidatesOnOwnerChangeOrStale() {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var h = new Harness(ConsumableFamily.POT_EVENT) {
            @Override
            public long checkpoint(ConsumptionId cid, int partition, com.kartaguez.pocoma.port.transaction.RecoveryReadContext read) {
                reads.incrementAndGet();
                return super.checkpoint(cid, partition, read);
            }
        };
        h.events = List.of();
        var engine = h.engine(1);
        // First empty fast-path call reads checkpoint once and skips effects.write.
        var p1 = engine.discoverEvents(h.lease, false);
        assertEquals(DiscoveryReports.Status.COMMITTED, p1.status());
        assertEquals(1, reads.get());
        // Second empty fast-path call with same ownerToken uses cached checkpoint (0 DB checkpoint reads).
        var p2 = engine.discoverEvents(h.lease, false);
        assertEquals(DiscoveryReports.Status.COMMITTED, p2.status());
        assertEquals(1, reads.get());
        // Owner change (new ownerToken after lease takeover) invalidates cache and re-reads DB.
        var newLease = new PartitionLease(h.lease.consumptionId(), h.lease.partitionId(), UUID.randomUUID(), h.lease.ownedUntil());
        var p3 = engine.discoverEvents(newLease, false);
        assertEquals(DiscoveryReports.Status.COMMITTED, p3.status());
        assertEquals(2, reads.get());
        // STALE_CHECKPOINT on non-empty window invalidates cache and refreshes on next pass.
        h.events = List.of(new NonBurnableReferences.Reference(1, u(1), "POT"));
        h.actualK = 1L;
        var stale = engine.discoverEvents(newLease, false);
        assertEquals(DiscoveryReports.Status.STALE_CHECKPOINT, stale.status());
        assertEquals(2, reads.get());
    }
}
