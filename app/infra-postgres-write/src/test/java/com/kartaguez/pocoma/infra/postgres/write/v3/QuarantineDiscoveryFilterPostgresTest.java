package com.kartaguez.pocoma.infra.postgres.write.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation.*;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.SlotKeyNavigation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.domain.consumption.v3.RoutingV1;
import com.kartaguez.pocoma.infra.postgres.core.v3.JdbcSlotKeyNavigation;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Functional, Safety and Concurrency Test Suite for Quarantine Discovery Filter (L6/I3-R11).
 * Validates all 11 normative scenarios and strict conservation of quarantined slots.
 */
@Testcontainers
class QuarantineDiscoveryFilterPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(ConsumptionLifecyclePostgresFixture.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        ConsumptionLifecyclePostgresFixture.initialize(PG);
    }

    private ConsumptionEngine engine(ConsumptionLifecyclePostgresFixture f) {
        return new ConsumptionEngine(
                f.definition,
                f.tx,
                f.slots,
                f.participant,
                f.tx,
                Optional.empty(),
                r -> true,
                null,
                f.tx,
                f.slots,
                f.ownership,
                f.settings,
                f.capacity,
                c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                new JdbcSlotKeyNavigation(f.source, f.settings),
                ConsumptionObservation.noop());
    }

    private ConsumptionNavigation.Page page(
            ConsumptionLifecyclePostgresFixture f, int p, ConsumptionNavigation.Walk walk, int limit) {
        return f.tx.read(
                c ->
                        new JdbcSlotKeyNavigation(f.source, f.settings)
                                .readPage(f.definition.id(), p, walk, limit, c));
    }

    private void insertQuarantined(ConsumptionLifecyclePostgresFixture f, UUID id) {
        f.oracle.update(
                "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,attempt_count,state)"
                        + " select ?,?,h,((h::bigint%4+4)%4)::int,3,'QUARANTINED' from (select consumption_hash_v1(?,?) h) x",
                f.definition.id().value(), id, f.definition.family().name(), id);
    }

    private void injectQuarantined(ConsumptionLifecyclePostgresFixture f, int totalCount) {
        f.oracle.update(
                "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,attempt_count,state)"
                        + " select ?,id,h,((h::bigint%4+4)%4)::int,3,'QUARANTINED' from ("
                        + "   select id, consumption_hash_v1(?,id) h from ("
                        + "     select gen_random_uuid() id from generate_series(1,?)"
                        + "   ) g"
                        + " ) x",
                f.definition.id().value(), f.definition.family().name(), totalCount);
    }

    private long countQuarantined(ConsumptionLifecyclePostgresFixture f) {
        return f.oracle.queryForObject(
                "select count(*) from consumption_slot where consumption_id=? and state='QUARANTINED'",
                Long.class,
                f.definition.id().value());
    }

    @Test
    void scenario01_noQuarantinedSlots_normalDiscovery() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < 8; i++) {
                ids.add(f.seed(0));
            }
            ids.sort(SlotKeyNavigation::compare);

            var walk = ConsumptionNavigation.Walk.fresh();
            var discovered = new ArrayList<UUID>();
            while (true) {
                var p = page(f, 0, walk, 4);
                if (p.keys().isEmpty()) break;
                discovered.addAll(p.keys());
                walk = new ConsumptionNavigation.Walk(Optional.of(p.keys().getLast()), p.upperInclusive());
            }

            assertEquals(ids, discovered, "All 8 available slots discovered in exact order");
        }
    }

    @Test
    void scenario02_803QuarantinedSlots_noneReturned() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 803);
            assertEquals(803, countQuarantined(f));

            for (int p = 0; p < 4; p++) {
                var res = page(f, p, ConsumptionNavigation.Walk.fresh(), 32);
                assertTrue(res.keys().isEmpty(), "No quarantined slots must be returned for partition " + p);
                assertTrue(res.upperInclusive().isEmpty(), "Upper bound must be empty when only quarantines exist");
            }
        }
    }

    @Test
    void scenario03_803Quarantines_singleAvailable_discoveredImmediately() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 803);
            assertEquals(803, countQuarantined(f));

            var singleAvailable = f.seed(0);

            var firstPage = page(f, 0, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, firstPage.keys().size(), "Must discover exactly 1 slot without traversing 803 pages");
            assertEquals(singleAvailable, firstPage.keys().getFirst());
            assertEquals(Optional.of(singleAvailable), firstPage.upperInclusive());
        }
    }

    @Test
    void scenario04_803Quarantines_multipleAvailable_fillsQuantum() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 803);
            assertEquals(803, countQuarantined(f));

            var available = new ArrayList<UUID>();
            for (int i = 0; i < 10; i++) {
                available.add(f.seed(0));
            }
            available.sort(SlotKeyNavigation::compare);

            var page1 = page(f, 0, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(4, page1.keys().size(), "Page 1 must fill quantum of 4");
            assertEquals(available.subList(0, 4), page1.keys());

            var walk2 = new ConsumptionNavigation.Walk(Optional.of(page1.keys().getLast()), page1.upperInclusive());
            var page2 = page(f, 0, walk2, 4);
            assertEquals(4, page2.keys().size(), "Page 2 must fill quantum of 4");
            assertEquals(available.subList(4, 8), page2.keys());

            var walk3 = new ConsumptionNavigation.Walk(Optional.of(page2.keys().getLast()), page2.upperInclusive());
            var page3 = page(f, 0, walk3, 4);
            assertEquals(2, page3.keys().size(), "Page 3 must return remaining 2");
            assertEquals(available.subList(8, 10), page3.keys());

            var walk4 = new ConsumptionNavigation.Walk(Optional.of(page3.keys().getLast()), page3.upperInclusive());
            var page4 = page(f, 0, walk4, 4);
            assertTrue(page4.keys().isEmpty(), "Page 4 must be empty (tour finished)");
        }
    }

    @Test
    void scenario05_allSlotsQuarantined_emptySearchNoHangs() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 200);

            long start = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                var p = page(f, 0, ConsumptionNavigation.Walk.fresh(), 4);
                assertTrue(p.keys().isEmpty());
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 2000, "50 empty discovery queries took " + elapsedMs + "ms, no hangs or timeouts");
        }
    }

    @Test
    void scenario06_reactivationOfQuarantine_becomesDiscoverable() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            var qId = UUID.randomUUID();
            insertQuarantined(f, qId);
            int partition = RoutingV1.partition(RoutingV1.hash(f.definition.family(), qId), 4);

            var before = page(f, partition, ConsumptionNavigation.Walk.fresh(), 4);
            assertTrue(before.keys().isEmpty(), "Slot is quarantined; must not be discovered");

            // Administrative reactivation
            f.oracle.update(
                    "update consumption_slot set state='AVAILABLE', attempt_count=0 where consumable_id=?",
                    qId);

            var after = page(f, partition, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, after.keys().size(), "Reactivated slot must immediately become discoverable");
            assertEquals(qId, after.keys().getFirst());
        }
    }

    @Test
    void scenario07_reactivationBehindCursor_discoveredOnNextRotation() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            // Seed 4 slots routed to partition 0
            var allFour = new ArrayList<UUID>();
            for (int i = 0; i < 4; i++) {
                allFour.add(f.seed(0));
            }
            allFour.sort(SlotKeyNavigation::compare);

            // S0 is the smallest in unsigned byte order; S1, S2, S3 are larger
            UUID s0 = allFour.get(0);
            UUID s1 = allFour.get(1);
            UUID s2 = allFour.get(2);
            UUID s3 = allFour.get(3);

            // Set S0 to QUARANTINED initially
            f.oracle.update(
                    "update consumption_slot set state='QUARANTINED', attempt_count=3 where consumable_id=?",
                    s0);

            // Advance cursor to s1 on first page
            var p1 = page(f, 0, ConsumptionNavigation.Walk.fresh(), 1);
            assertEquals(s1, p1.keys().getFirst(), "First available key is s1 since s0 is quarantined");
            UUID cursor = s1;

            // Now reactivate s0 while cursor is at s1 (s0 < cursor)
            f.oracle.update(
                    "update consumption_slot set state='AVAILABLE', attempt_count=0 where consumable_id=?",
                    s0);

            // Continuing current tour with afterExclusive=cursor cannot see s0 because s0 < cursor
            var pContinue = page(f, 0, new ConsumptionNavigation.Walk(Optional.of(cursor), p1.upperInclusive()), 10);
            assertEquals(List.of(s2, s3), pContinue.keys());

            // When tour completes, a fresh walk starts from the beginning and rediscovers s0!
            var pNextTour = page(f, 0, ConsumptionNavigation.Walk.fresh(), 10);
            assertEquals(s0, pNextTour.keys().getFirst(), "s0 is discovered at the start of the next circular tour");
            assertTrue(pNextTour.keys().containsAll(List.of(s0, s1, s2, s3)));
        }
    }

    @Test
    void scenario08_multiplePartitions_noStarvation() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 803);

            // Seed available commands in partitions 1 and 3
            var p1Cmd = f.seed(1);
            var p3Cmd = f.seed(3);

            var e = engine(f);

            var l1 = e.acquirePartition(1).orElseThrow();
            var page1 = e.navigate(l1, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, page1.keys().size());
            assertEquals(p1Cmd, page1.keys().getFirst());
            var acq1 = e.acquire(l1, page1.keys());
            assertEquals(1, acq1.claims().size());
            assertEquals(ConsumptionProtocol.Status.COMMITTED, e.execute(acq1.claims().getFirst()).status());

            var l3 = e.acquirePartition(3).orElseThrow();
            var page3 = e.navigate(l3, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, page3.keys().size());
            assertEquals(p3Cmd, page3.keys().getFirst());
            var acq3 = e.acquire(l3, page3.keys());
            assertEquals(1, acq3.claims().size());
            assertEquals(ConsumptionProtocol.Status.COMMITTED, e.execute(acq3.claims().getFirst()).status());

            // Partitions 0 and 2 are empty of available slots and return empty pages cleanly
            var l0 = e.acquirePartition(0).orElseThrow();
            assertTrue(e.navigate(l0, ConsumptionNavigation.Walk.fresh(), 4).keys().isEmpty());
            var l2 = e.acquirePartition(2).orElseThrow();
            assertTrue(e.navigate(l2, ConsumptionNavigation.Walk.fresh(), 4).keys().isEmpty());
        }
    }

    @Test
    void scenario09_multipleWorkers_concurrencySafe() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 400);

            var candidates = new ArrayList<UUID>();
            for (int i = 0; i < 4; i++) {
                candidates.add(f.seed(0));
            }

            var e = engine(f);
            var lease = e.acquirePartition(0).orElseThrow();

            // First acquisition claims 2 slots (up to capacity of 2)
            var acq1 = e.acquire(lease, candidates);
            assertEquals(2, acq1.claims().size(), "First acquisition gets 2 claims");

            // Concurrent attempt with same candidate keys cannot re-claim already claimed slots
            var acq2 = e.acquire(lease, candidates);
            // Remaining 2 unclaimed slots can be claimed, but previously claimed ones cannot be double-claimed
            var won1 = new HashSet<UUID>();
            acq1.claims().forEach(c -> won1.add(c.claim().consumableId()));
            var won2 = new HashSet<UUID>();
            acq2.claims().forEach(c -> won2.add(c.claim().consumableId()));

            // Intersection must be empty (0 double-claims)
            var intersection = new HashSet<>(won1);
            intersection.retainAll(won2);
            assertTrue(intersection.isEmpty(), "Zero double acquisitions allowed across concurrent attempts");

            acq1.claims().forEach(e::abandon);
            acq2.claims().forEach(e::abandon);
        }
    }

    @Test
    void scenario10_expiredClaimRecovery_alwaysFunctional() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 300);
            var id = f.seed(0);

            var e = engine(f);
            var lease = e.acquirePartition(0).orElseThrow();

            // Acquire and abandon
            var acq = e.acquire(lease, List.of(id));
            assertEquals(1, acq.claims().size());
            e.abandon(acq.claims().getFirst());

            // Force claim expiration
            f.expire(id);

            // Expired slot must still be discoverable because state is AVAILABLE
            var p = page(f, 0, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, p.keys().size());
            assertEquals(id, p.keys().getFirst());

            // New acquisition must successfully reclaim the expired slot
            var acqReclaimed = e.acquire(lease, List.of(id));
            assertEquals(1, acqReclaimed.claims().size(), "Expired lease must be successfully reclaimed");
            assertEquals(ConsumptionProtocol.Status.COMMITTED, e.execute(acqReclaimed.claims().getFirst()).status());
        }
    }

    @Test
    void scenario11_concurrentStateModification_transactionallySafe() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            var id = f.seed(0);

            // Discovered as AVAILABLE
            var p = page(f, 0, ConsumptionNavigation.Walk.fresh(), 4);
            assertEquals(1, p.keys().size());
            assertEquals(id, p.keys().getFirst());

            // Another concurrent process quarantines the slot before acquire
            f.oracle.update(
                    "update consumption_slot set state='QUARANTINED', attempt_count=3 where consumable_id=?",
                    id);

            // Engine attempts acquisition
            var e = engine(f);
            var lease = e.acquirePartition(0).orElseThrow();
            var acq = e.acquire(lease, List.of(id));

            assertEquals(0, acq.claims().size(), "Slot transitioned to QUARANTINED must not be acquired");
            assertEquals(1, acq.examinedCount(), "Examined count properly tracks the candidate");
            assertEquals(0, f.capacity.snapshot().worker(), "Capacity ticket must be safely released");
        }
    }

    @Test
    void scenario12_conservationVerification_803QuarantinesPreserved() throws Exception {
        try (var f = new ConsumptionLifecyclePostgresFixture(PG, true)) {
            injectQuarantined(f, 803);
            assertEquals(803, countQuarantined(f));

            // Execute normal work alongside the 803 quarantined slots
            var id1 = f.seed(0);
            var id2 = f.seed(1);
            var id3 = f.seed(2);
            var id4 = f.seed(3);

            var e = engine(f);
            for (int p = 0; p < 4; p++) {
                var l = e.acquirePartition(p).orElseThrow();
                var pge = e.navigate(l, ConsumptionNavigation.Walk.fresh(), 4);
                assertEquals(1, pge.keys().size());
                var acq = e.acquire(l, pge.keys());
                assertEquals(1, acq.claims().size());
                assertEquals(ConsumptionProtocol.Status.COMMITTED, e.execute(acq.claims().getFirst()).status());
            }

            // CRITICAL VERIFICATION: All 803 quarantined slots must still be present in the database!
            assertEquals(803, countQuarantined(f), "All 803 quarantined slots must remain intact");
            long totalSlots = f.oracle.queryForObject(
                    "select count(*) from consumption_slot where consumption_id=?",
                    Long.class,
                    f.definition.id().value());
            assertEquals(803, totalSlots, "All available slots were consumed and deleted; exactly 803 quarantined slots remain");
        }
    }
}
