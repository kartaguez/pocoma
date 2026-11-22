package com.kartaguez.pocoma.runtime.task;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.MicrometerPotBalanceObservation;
import com.kartaguez.pocoma.compose.polling.materialize.MicrometerPotVersionObservation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.pot.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotBalance;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotVersion;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcPartitionOwnershipStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcSubobjectVersionStore;
import com.kartaguez.pocoma.infra.postgres.source.JdbcPotBalanceSource;
import com.kartaguez.pocoma.infra.postgres.source.JdbcPotVersionSource;
import com.kartaguez.pocoma.infra.postgres.write.pot.*;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class PotVersionAndBalanceJointIncrementalPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(SubobjectPostgresFixture.IMAGE).withDatabaseName("i1_read");

    private static final PotBalanceCalculator BALANCE_ORACLE = new PotBalanceCalculator();

    @org.junit.jupiter.api.BeforeAll
    static void init() {
        SubobjectPostgresFixture.initialize(PG);
    }

    @Test
    void jointOutOfOrderConcurrencyRecoveryIdempotenceAndReconstructionMatchBothOracles()
            throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "joint-a3-a4", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var sh3 = new ShareholderId(UUID.randomUUID());
            var sh4 = new ShareholderId(UUID.randomUUID());
            var sh5 = new ShareholderId(UUID.randomUUID());
            var sh6 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var exp2 = new ExpenseId(UUID.randomUUID());
            var exp3 = new ExpenseId(UUID.randomUUID());
            var exp4 = new ExpenseId(UUID.randomUUID());

            var events = new TreeMap<Long, UUID>();
            // v1: CreatePot
            events.put(
                    1L,
                    executeWrite(
                            f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Joint A3+A4"))));
            // v2: AddShareholders (sh1 attached, sh2..sh5 free)
            events.put(
                    2L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            1,
                            "AddShareholders",
                            AddShareholders.legacy(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1, new Name("Alice"), Optional.of(creator), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("Bob"), Optional.empty(), w(2, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh3, new Name("Charlie"), Optional.empty(), w(3, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh4, new Name("Dave"), Optional.empty(), w(1, 2)),
                                            new ShareholderTransitions.Creation(
                                                    sh5, new Name("Eve"), Optional.empty(), w(1, 1))))));
            // v3: CreateExpense exp1 (100% default weights)
            events.put(
                    3L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            2,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(
                                            potId, exp1, new Label("Dinner"), LocalDate.of(2026, 3, 1)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(60, 1)),
                                            sh1,
                                            Map.of(
                                                    sh1,
                                                    Optional.empty(),
                                                    sh2,
                                                    Optional.empty(),
                                                    sh3,
                                                    Optional.empty())))));
            // v4: CreateExpense exp2 (mixed weights)
            events.put(
                    4L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(
                                            potId, exp2, new Label("Taxi"), LocalDate.of(2026, 3, 2)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp2,
                                            new Amount(new Fraction(45, 1)),
                                            sh2,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.of(w(5, 2)))))));
            // v5: CreateExpense exp3 (100% specific weights -> fixedNet)
            events.put(
                    5L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            4,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(
                                            potId, exp3, new Label("Tickets"), LocalDate.of(2026, 3, 3)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp3,
                                            new Amount(new Fraction(100, 1)),
                                            sh3,
                                            Map.of(
                                                    sh1, Optional.of(w(1, 1)), sh3, Optional.of(w(3, 1)))))));
            // v6: UpdateExpenseDetails (non-financial)
            events.put(
                    6L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            5,
                            "UpdateExpenseDetails",
                            new UpdateExpenseDetails(
                                    exp1, new Label("Dinner updated"), LocalDate.of(2026, 3, 4))));
            // v7: UpdateExpenseFinancials exp1
            events.put(
                    7L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            6,
                            "UpdateExpenseFinancials",
                            new UpdateExpenseFinancials(
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(90, 1)),
                                            sh2,
                                            Map.of(sh2, Optional.empty(), sh3, Optional.empty())))));
            // v8: UpdateShareholderWeights (retroactive weight change)
            events.put(
                    8L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            7,
                            "UpdateShareholderWeights",
                            new UpdateShareholderWeights(
                                    List.of(
                                            new ShareholderTransitions.WeightIntent(sh1, w(5, 3)),
                                            new ShareholderTransitions.WeightIntent(sh2, w(7, 2))))));
            // v9: UpdateShareholderDetails on attached sh1 -> A3 global_shareholder_effect fallback,
            //     while A4 remains INCREMENTAL with 0 expenses read!
            events.put(
                    9L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            8,
                            "UpdateShareholderDetails",
                            new UpdateShareholderDetails(
                                    List.of(
                                            new ShareholderTransitions.DetailsIntent(
                                                    sh1, new Name("Alice Renamed"), Optional.of(creator))))));
            // v10: DeleteExpense exp1 (logical delete)
            events.put(
                    10L,
                    executeWrite(f, creator, potId, 9, "DeleteExpense", new DeleteExpense(exp1)));
            // v11: AddShareholders sh6 (D1 re-indexing in A4)
            events.put(
                    11L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            10,
                            "AddShareholders",
                            AddShareholders.legacy(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh6, new Name("Frank"), Optional.empty(), w(4, 1))))));
            // v12: CreateExpense exp4 (distinct bucket with sh1, sh4, sh6)
            events.put(
                    12L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            11,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(
                                            potId, exp4, new Label("Hotel"), LocalDate.of(2026, 3, 5)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp4,
                                            new Amount(new Fraction(210, 1)),
                                            sh6,
                                            Map.of(
                                                    sh1,
                                                    Optional.empty(),
                                                    sh4,
                                                    Optional.empty(),
                                                    sh6,
                                                    Optional.empty())))));
            // v13: DeleteShareholder sh5 (free unused) -> A4 shareholder_deleted fallback,
            //      while A3 remains INCREMENTAL!
            events.put(
                    13L,
                    executeWrite(
                            f, creator, potId, 12, "DeleteShareholder", new DeleteShareholder(sh5)));
            // v14: UpdateShareholderWeights after D4 fallback (both A3 & A4 incremental)
            events.put(
                    14L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            13,
                            "UpdateShareholderWeights",
                            new UpdateShareholderWeights(
                                    List.of(new ShareholderTransitions.WeightIntent(sh6, w(3, 2))))));
            // v15: DeletePot (terminal D: A3 writes deleted PotVersion, A4 writes neither balance nor aggregate)
            events.put(15L, executeWrite(f, creator, potId, 14, "DeletePot", new DeletePot()));

            var verSource = new JdbcPotVersionSource(f.primary);
            var subobjects = new JdbcSubobjectVersionStore(f.read.source, f.read.tx);
            var verStore = new JdbcPotVersionStore(f.read.source, f.read.tx);
            var balSource = new JdbcPotBalanceSource(f.primary);
            var balStore = new JdbcPotBalanceStore(f.read.source, f.read.tx);

            var registry = new SimpleMeterRegistry();
            var verObs = new MicrometerPotVersionObservation(registry, Tags.empty());
            var balObs = new MicrometerPotBalanceObservation(registry, Tags.empty());
            var potVersion =
                    new MaterializePotVersion(
                            verSource,
                            subobjects,
                            verStore,
                            MaterializePotVersion.DEFAULT_MAX_DELTA_DISTANCE,
                            verObs);
            var potBalance =
                    new MaterializePotBalance(
                            balSource, balStore, MaterializePotBalance.Settings.DEFAULT, balObs);

            // Seed v1 on both
            materializeVersion(f, potVersion, events.get(1L));
            materializeBalance(f, potBalance, events.get(1L));

            // Out-of-order and asymmetric progression: A3 and A4 run concurrently on different versions
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CountDownLatch(1);
                var fVer =
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (long v : new long[] {6L, 3L, 8L, 2L, 5L, 4L, 7L}) {
                                        materializeVersion(f, potVersion, events.get(v));
                                    }
                                    return true;
                                });
                var fBal =
                        pool.submit(
                                () -> {
                                    start.await();
                                    for (long v : new long[] {5L, 8L, 2L, 7L, 3L, 6L, 4L}) {
                                        materializeBalance(f, potBalance, events.get(v));
                                    }
                                    return true;
                                });
                start.countDown();
                assertTrue(fVer.get(30, TimeUnit.SECONDS));
                assertTrue(fBal.get(30, TimeUnit.SECONDS));
            }

            // Prove independent fallback on v9: A3 falls back (global_shareholder_effect), A4 stays incremental (0 expenses read, 100% physical dedup)
            double balExpensesBeforeV9 =
                    registry.get("pocoma.v3.potbalance.delta.expenses").summary().totalAmount();
            double balIncBeforeV9 =
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count();
            int shSetsBeforeV9 = f.read.oracle.queryForObject("select count(*) from read_pot_shareholder_set", Integer.class);
            int balContentsBeforeV9 = f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class);
            int aggContentsBeforeV9 = f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class);
            double verGlobalBeforeV9 =
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "global_shareholder_effect")
                            .counter()
                            .count();

            materializeVersion(f, potVersion, events.get(9L));
            materializeBalance(f, potBalance, events.get(9L));

            assertEquals(
                    verGlobalBeforeV9 + 1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "global_shareholder_effect")
                            .counter()
                            .count(),
                    "A3 must fall back on UpdateShareholderDetails of attached shareholder");
            assertEquals(
                    balIncBeforeV9 + 1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count(),
                    "A4 must remain incremental on UpdateShareholderDetails");
            assertEquals(
                    shSetsBeforeV9,
                    f.read.oracle.queryForObject("select count(*) from read_pot_shareholder_set", Integer.class),
                    "V4 must reuse shareholder set on UpdateShareholderDetails");
            assertEquals(
                    balContentsBeforeV9,
                    f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class),
                    "V4 must physically deduplicate balance content on UpdateShareholderDetails");
            assertEquals(
                    aggContentsBeforeV9,
                    f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class),
                    "V4 must physically deduplicate aggregate content on UpdateShareholderDetails");
            assertEquals(
                    balExpensesBeforeV9,
                    registry.get("pocoma.v3.potbalance.delta.expenses").summary().totalAmount(),
                    "A4 must read 0 expenses on UpdateShareholderDetails");

            // Materialize v12, v10, v11 out of order on both
            for (long v : new long[] {12L, 10L, 11L}) {
                materializeVersion(f, potVersion, events.get(v));
                materializeBalance(f, potBalance, events.get(v));
            }

            // Prove independent fallback on v13 (DeleteShareholder on free sh5):
            // A4 falls back (shareholder_deleted), while A3 stays incremental!
            double verIncBeforeV13 =
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count();
            double balDelShBeforeV13 =
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "shareholder_deleted")
                            .counter()
                            .count();

            materializeVersion(f, potVersion, events.get(13L));
            materializeBalance(f, potBalance, events.get(13L));

            assertEquals(
                    verIncBeforeV13 + 1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count(),
                    "A3 must remain incremental when deleting a free shareholder");
            assertEquals(
                    balDelShBeforeV13 + 1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "shareholder_deleted")
                            .counter()
                            .count(),
                    "A4 must fall back on DeleteShareholder (D4)");

            // v14 (both resume incremental) and v15 (terminal DeletePot)
            for (long v : new long[] {14L, 15L}) {
                materializeVersion(f, potVersion, events.get(v));
                materializeBalance(f, potBalance, events.get(v));
            }

            // Idempotent replay of v8 and v14 on both participants
            materializeVersion(f, potVersion, events.get(8L));
            materializeBalance(f, potBalance, events.get(8L));
            materializeVersion(f, potVersion, events.get(14L));
            materializeBalance(f, potBalance, events.get(14L));

            // Strict oracle verification for every single version v1..v15 on BOTH projections
            verifyAllVersionsAgainstOracles(
                    f, potId, events, verSource, subobjects, verStore, balSource, balStore, potVersion, potBalance);

            // Verify atomic publication pointer and aggregate row count (14 active versions, 0 for terminal v15)
            assertEquals(
                    15L,
                    f.read.oracle.queryForObject(
                            "select pot_version from read_current_pot where pot_id=?",
                            Long.class,
                            potId.value()));
            assertEquals(
                    14,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=?",
                            Integer.class,
                            potId.value()));
            assertEquals(
                    0,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=? and pot_version=15",
                            Integer.class,
                            potId.value()));
            assertTrue(
                    registry.get("pocoma.v3.potversion.reference.distance").summary().max() >= 5.0);
            assertTrue(
                    registry.get("pocoma.v3.potbalance.reference.distance").summary().max() >= 4.0);

            // Full READ reconstruction: wipe all READ projection tables (including read_pot_balance_aggregate)
            // and replay all 15 events sequentially, then re-verify exact equality against both oracles.
            resetReadProjectionTables(f);
            for (long v = 1L; v <= 15L; v++) {
                materializeVersion(f, potVersion, events.get(v));
                materializeBalance(f, potBalance, events.get(v));
            }
            verifyAllVersionsAgainstOracles(
                    f, potId, events, verSource, subobjects, verStore, balSource, balStore, potVersion, potBalance);
        }
    }

    @Test
    void independentFallbacksForBucketCapAndAsymmetricMaxDeltaDistance() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "joint-indep-fb", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var sh3 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var exp2 = new ExpenseId(UUID.randomUUID());
            var exp3 = new ExpenseId(UUID.randomUUID());

            UUID e1 =
                    executeWrite(
                            f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Indep Fallback")));
            UUID e2 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            1,
                            "AddShareholders",
                            AddShareholders.legacy(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1, new Name("A"), Optional.of(creator), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("B"), Optional.empty(), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh3, new Name("C"), Optional.empty(), w(1, 1)))));
            // Bucket 1: {sh1, sh2}
            UUID e3 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            2,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp1, new Label("E1"), LocalDate.of(2026, 3, 1)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(20, 1)),
                                            sh1,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.empty()))));
            // Bucket 2: {sh2, sh3}
            UUID e4 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp2, new Label("E2"), LocalDate.of(2026, 3, 2)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp2,
                                            new Amount(new Fraction(30, 1)),
                                            sh2,
                                            Map.of(sh2, Optional.empty(), sh3, Optional.empty()))));
            // Bucket 3: {sh1, sh3} -> exceeds maxAggregateBuckets=2 for A4, but A3 stays incremental!
            UUID e5 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            4,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp3, new Label("E3"), LocalDate.of(2026, 3, 3)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp3,
                                            new Amount(new Fraction(40, 1)),
                                            sh3,
                                            Map.of(sh1, Optional.empty(), sh3, Optional.empty()))));

            var verSource = new JdbcPotVersionSource(f.primary);
            var subobjects = new JdbcSubobjectVersionStore(f.read.source, f.read.tx);
            var verStore = new JdbcPotVersionStore(f.read.source, f.read.tx);
            var balSource = new JdbcPotBalanceSource(f.primary);
            var balStore = new JdbcPotBalanceStore(f.read.source, f.read.tx);

            var registry = new SimpleMeterRegistry();
            // A3 has maxDeltaDistance=1 (narrow), A4 has maxDeltaDistance=64 and maxAggregateBuckets=2
            var potVersion =
                    new MaterializePotVersion(
                            verSource,
                            subobjects,
                            verStore,
                            1L,
                            new MicrometerPotVersionObservation(registry, Tags.empty()));
            var potBalance =
                    new MaterializePotBalance(
                            balSource,
                            balStore,
                            new MaterializePotBalance.Settings(64, 64, 2, 10_000),
                            new MicrometerPotBalanceObservation(registry, Tags.empty()));

            // v1: both initial_version
            materializeVersion(f, potVersion, e1);
            materializeBalance(f, potBalance, e1);

            // Jump directly to v4 (distance = 3):
            // A3 must fall back (no_reference because 3 > 1), while A4 stays INCREMENTAL (3 <= 64, 2 buckets <= 2)!
            materializeVersion(f, potVersion, e4);
            materializeBalance(f, potBalance, e4);

            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "no_reference")
                            .counter()
                            .count());
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count());

            // Advance to v5 (distance = 1):
            // A3 stays INCREMENTAL (1 <= 1), while A4 hits BUCKET_CAP (3 > 2) and skips aggregate persistence!
            materializeVersion(f, potVersion, e5);
            materializeBalance(f, potBalance, e5);

            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "incremental")
                            .tag("reason", "incremental_hit")
                            .counter()
                            .count());
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "bucket_cap")
                            .counter()
                            .count());
            assertEquals(
                    0,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=? and pot_version=5",
                            Integer.class,
                            potId.value()));

            // Both projections still match their exact oracles at v4 and v5
            for (var entry : Map.of(4L, e4, 5L, e5).entrySet()) {
                long v = entry.getKey();
                UUID ev = entry.getValue();
                PotVersion expectedV = f.sourceOwner.read(c -> verSource.composition(ev, c));
                PotVersion actualV = f.read.tx.read(c -> verStore.read(potId.value(), v, c).orElseThrow());
                assertEquals(expectedV, actualV);
                Optional<PotBalance> expectedB =
                        oracleBalance(f.sourceOwner.read(c -> balSource.load(ev, c)));
                Optional<PotBalance> actualB = f.read.tx.read(c -> balStore.read(potId.value(), v, c));
                assertEquals(expectedB, actualB);
            }
        }
    }

    @Test
    void jointLeaseRevocationAndInterruptionPreserveAtomicityAndIndependence() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "joint-lease-revocation", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());

            var events = new TreeMap<Long, UUID>();
            events.put(
                    1L,
                    executeWrite(
                            f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Lease Revocation"))));
            events.put(
                    2L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            1,
                            "AddShareholders",
                            AddShareholders.legacy(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1, new Name("Alice"), Optional.of(creator), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("Bob"), Optional.empty(), w(2, 1))))));
            events.put(
                    3L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            2,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp1, new Label("Train"), LocalDate.of(2026, 11, 20)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(90, 1)),
                                            sh1,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.empty())))));
            events.put(
                    4L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "UpdateExpenseFinancials",
                            new UpdateExpenseFinancials(
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(120, 1)),
                                            sh2,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.empty())))));

            var verSource = new JdbcPotVersionSource(f.primary);
            var subobjects = new JdbcSubobjectVersionStore(f.read.source, f.read.tx);
            var verStore = new JdbcPotVersionStore(f.read.source, f.read.tx);
            var balSource = new JdbcPotBalanceSource(f.primary);
            var balStore = new JdbcPotBalanceStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var potVersion =
                    new MaterializePotVersion(
                            verSource,
                            subobjects,
                            verStore,
                            MaterializePotVersion.DEFAULT_MAX_DELTA_DISTANCE,
                            new MicrometerPotVersionObservation(registry, Tags.empty()));
            var potBalance =
                    new MaterializePotBalance(
                            balSource,
                            balStore,
                            MaterializePotBalance.Settings.DEFAULT,
                            new MicrometerPotBalanceObservation(registry, Tags.empty()));

            var verDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(UUID.fromString("11111111-1111-5111-8111-111111111111")),
                            ConsumableFamily.POT_EVENT,
                            ConsumptionDefinition.Authority.READ);
            var balDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(UUID.fromString("22222222-2222-5222-8222-222222222222")),
                            ConsumableFamily.POT_EVENT,
                            ConsumptionDefinition.Authority.READ);
            for (var def : List.of(verDef, balDef)) {
                f.read.oracle.update(
                        "insert into consumption_routing_contract(consumption_id,family,routing_version,partition_count) values (?,?,'sha256-v1',4)",
                        def.id().value(),
                        def.family().name());
                for (int p = 0; p < 4; p++) {
                    f.read.oracle.update(
                            "insert into partition_ownership(consumption_id,partition_id) values (?,?)",
                            def.id().value(),
                            p);
                }
                for (UUID ev : events.values()) {
                    f.read.oracle.update(
                            "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                + " select ?,?,h,((h::bigint%4+4)%4)::int from (select"
                                + " consumption_hash_v1('POT_EVENT',?) h)x",
                            def.id().value(),
                            ev,
                            ev);
                }
            }

            var verEngine =
                    new ConsumptionEngine(
                            verDef,
                            f.read.tx,
                            f.read.slots,
                            potVersion,
                            f.sourceOwner,
                            Optional.of(f.references),
                            r -> true,
                            f.admissions,
                            f.read.tx,
                            f.read.slots,
                            f.read.ownership,
                            f.read.settings,
                            new WorkerCapacity(4, Map.of(verDef.id(), 4)),
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
            var balEngine1 =
                    new ConsumptionEngine(
                            balDef,
                            f.read.tx,
                            f.read.slots,
                            potBalance,
                            f.sourceOwner,
                            Optional.of(f.references),
                            r -> true,
                            f.admissions,
                            f.read.tx,
                            f.read.slots,
                            f.read.ownership,
                            f.read.settings,
                            new WorkerCapacity(4, Map.of(balDef.id(), 4)),
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
            var balEngine2 =
                    new ConsumptionEngine(
                            balDef,
                            f.read.tx,
                            f.read.slots,
                            potBalance,
                            f.sourceOwner,
                            Optional.of(f.references),
                            r -> true,
                            f.admissions,
                            f.read.tx,
                            f.read.slots,
                            f.read.ownership,
                            f.read.settings,
                            new WorkerCapacity(4, Map.of(balDef.id(), 4)),
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());

            // Both projections materialize v1
            var verLeases = new HashMap<Integer, com.kartaguez.pocoma.contract.consumption.spi.PartitionLease>();
            int p1 = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, events.get(1L)), 4);
            var verLease1 = verEngine.acquirePartition(p1).orElseThrow();
            verLeases.put(p1, verLease1);
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED,
                    verEngine.execute(verEngine.acquire(verLease1, List.of(events.get(1L))).claims().getFirst()).status());
            var bal1Lease1 = balEngine1.acquirePartition(p1).orElseThrow();
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED,
                    balEngine1.execute(balEngine1.acquire(bal1Lease1, List.of(events.get(1L))).claims().getFirst()).status());

            // Worker 1 acquires claim on PotBalance v2, then its lease & slot claim expire and Worker 2 takes over
            int p2 = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, events.get(2L)), 4);
            var staleLease = p2 == p1 ? bal1Lease1 : balEngine1.acquirePartition(p2).orElseThrow();
            var staleClaim = balEngine1.acquire(staleLease, List.of(events.get(2L))).claims().getFirst();

            // Worker 1 stops owning both partitions it used. Versions 3 and 4 may route to either.
            assertEquals(p1 == p2 ? 1 : 2, f.read.oracle.update(
                    "update partition_ownership set owner_token = null, owned_until = null"
                            + " where consumption_id=? and partition_id in (?,?) and owner_token is not null",
                    balDef.id().value(), p1, p2));
            f.read.oracle.update(
                    "update consumption_slot set claimed_until = now() - interval '1 second' where consumption_id=? and consumable_id=?",
                    balDef.id().value(),
                    events.get(2L));
            var bal2Leases = new HashMap<Integer, com.kartaguez.pocoma.contract.consumption.spi.PartitionLease>();
            var newLease = balEngine2.acquirePartition(p2).orElseThrow();
            bal2Leases.put(p2, newLease);
            assertNotEquals(staleLease.ownerToken(), newLease.ownerToken());
            var newClaim2 = balEngine2.acquire(newLease, List.of(events.get(2L))).claims().getFirst();

            // Meanwhile, PotVersion advances independently through v2, v3, v4
            for (long v : new long[] {2L, 3L, 4L}) {
                int pv = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, events.get(v)), 4);
                var vLease = verLeases.computeIfAbsent(pv, k -> verEngine.acquirePartition(k).orElseThrow());
                var vRes = verEngine.execute(verEngine.acquire(vLease, List.of(events.get(v))).claims().getFirst());
                assertEquals(ConsumptionProtocol.Status.COMMITTED, vRes.status());
            }

            // Stale PotBalance worker attempts to execute v2 -> must be fenced and expose 0 partial rows
            var staleResult = balEngine1.execute(staleClaim);
            assertNotEquals(ConsumptionProtocol.Status.COMMITTED, staleResult.status());
            assertTrue(
                    f.read.tx.read(c -> balStore.read(potId.value(), 2L, c)).isEmpty(),
                    "Fenced PotBalance worker must not expose partial balance for v2");
            assertEquals(
                    0,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance where pot_id=? and pot_version=2",
                            Integer.class,
                            potId.value()));

            // Worker 2 with valid lease & claim executes v2, then completes v3, v4 cleanly
            assertEquals(ConsumptionProtocol.Status.COMMITTED, balEngine2.execute(newClaim2).status());
            for (long v : new long[] {3L, 4L}) {
                int pb = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, events.get(v)), 4);
                var bLease = bal2Leases.computeIfAbsent(pb, k -> balEngine2.acquirePartition(k).orElseThrow());
                var bRes = balEngine2.execute(balEngine2.acquire(bLease, List.of(events.get(v))).claims().getFirst());
                assertEquals(ConsumptionProtocol.Status.COMMITTED, bRes.status());
            }

            verifyAllVersionsAgainstOracles(
                    f, potId, events, verSource, subobjects, verStore, balSource, balStore, potVersion, potBalance);
        }
    }

    private static void verifyAllVersionsAgainstOracles(
            InterAuthorityPostgresFixture f,
            PotId potId,
            Map<Long, UUID> events,
            JdbcPotVersionSource verSource,
            JdbcSubobjectVersionStore subobjects,
            JdbcPotVersionStore verStore,
            JdbcPotBalanceSource balSource,
            JdbcPotBalanceStore balStore,
            MaterializePotVersion potVersion,
            MaterializePotBalance potBalance) {
        for (var entry : events.entrySet()) {
            long version = entry.getKey();
            UUID eventId = entry.getValue();
            PotVersion expectedVersion = f.sourceOwner.read(c -> verSource.composition(eventId, c));
            PotVersion actualVersion =
                    f.read.tx.read(c -> verStore.read(potId.value(), version, c).orElseThrow());
            assertEquals(expectedVersion, actualVersion, "PotVersion mismatch at v=" + version);

            List<SubobjectVersion> expectedSubs =
                    f.sourceOwner.read(
                            c -> verSource.contents(expectedVersion, expectedVersion.keys(), c));
            Map<SubobjectKey, SubobjectVersion> actualSubs =
                    f.read.tx.read(c -> subobjects.readAll(expectedVersion.keys(), c));
            assertEquals(expectedSubs.size(), actualSubs.size(), "Subobject count mismatch at v=" + version);
            for (var expectedSub : expectedSubs) {
                assertEquals(
                        expectedSub,
                        actualSubs.get(expectedSub.key()),
                        "Subobject mismatch at v=" + version + " key=" + expectedSub.key());
            }

            Optional<PotBalance> expectedBalance =
                    oracleBalance(f.sourceOwner.read(c -> balSource.load(eventId, c)));
            Optional<PotBalance> actualBalance =
                    f.read.tx.read(c -> balStore.read(potId.value(), version, c));
            assertEquals(expectedBalance, actualBalance, "PotBalance mismatch at v=" + version);

            var verRecovery = potVersion.prepareRecovery(eventId, prepReads(f));
            assertEquals(
                    ConsumptionParticipant.EffectEvidence.SATISFIED,
                    f.read.tx.read(verRecovery::observe),
                    "PotVersion recovery evidence mismatch at v=" + version);
            var balRecovery = potBalance.prepareRecovery(eventId, prepReads(f));
            assertEquals(
                    ConsumptionParticipant.EffectEvidence.SATISFIED,
                    f.read.tx.read(balRecovery::observe),
                    "PotBalance recovery evidence mismatch at v=" + version);
        }
    }

    private static void resetReadProjectionTables(InterAuthorityPostgresFixture f) {
        for (String table :
                List.of(
                        "read_pot_balance_aggregate",
                        "read_pot_balance_aggregate_content",
                        "read_pot_balance",
                        "read_pot_balance_content",
                        "read_pot_shareholder_set",
                        "read_current_pot",
                        "read_pot_version",
                        "read_pot_version_node",
                        "read_pot_user_set",
                        "read_subobject_version")) {
            f.read.oracle.execute("alter table " + table + " disable trigger user");
            f.read.oracle.execute("delete from " + table);
            f.read.oracle.execute("alter table " + table + " enable trigger user");
        }
    }

    private static Optional<PotBalance> oracleBalance(PotFinancialState state) {
        return BALANCE_ORACLE
                .calculate(state)
                .map(
                        b -> {
                            var values = new LinkedHashMap<UUID, Fraction>();
                            b.values().forEach((k, v) -> values.put(k.value(), v));
                            return new PotBalance(state.potId().value(), state.version(), values);
                        });
    }

    private static Weight w(long n, long d) {
        return new Weight(new Fraction(n, d));
    }

    private static PreparationReads prepReads(InterAuthorityPostgresFixture f) {
        return new PreparationReads() {
            @Override
            public <T> T source(Function<RecoveryReadContext, T> action) {
                return f.sourceOwner.read(action);
            }

            @Override
            public <T> T effects(Function<RecoveryReadContext, T> action) {
                return f.read.tx.read(action);
            }
        };
    }

    private static void materializeVersion(
            InterAuthorityPostgresFixture f, MaterializePotVersion participant, UUID eventId) {
        var prepared = participant.prepare(eventId, prepReads(f)).orElseThrow();
        f.read.tx.write(
                () -> {
                    prepared.finalizeIn(prepared::requireCompletionReady);
                    return null;
                });
    }

    private static void materializeBalance(
            InterAuthorityPostgresFixture f, MaterializePotBalance participant, UUID eventId) {
        var prepared = participant.prepare(eventId, prepReads(f)).orElseThrow();
        f.read.tx.write(
                () -> {
                    prepared.finalizeIn(prepared::requireCompletionReady);
                    return null;
                });
    }

    private static UUID executeWrite(
            InterAuthorityPostgresFixture f,
            UserId actorUserId,
            PotId potId,
            long observedVersion,
            String operation,
            PotCommand.Intent intent) {
        var request = UUID.randomUUID();
        f.oracle.update(
                "with cmd as (insert into"
                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                    + " values (?,'i2','actor','fixture',?,?,?,?::jsonb) returning request_id),"
                    + " pos as (update v3_request_counter set position=position+1 where"
                    + " family='COMMAND' returning position) insert into"
                    + " v3_request_position(family,position,request_id) select"
                    + " 'COMMAND',pos.position,cmd.request_id from cmd,pos",
                request,
                potId.value(),
                observedVersion,
                operation,
                PotCommandJson.encode(intent));
        var definition =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                        ConsumableFamily.COMMAND,
                        ConsumptionDefinition.Authority.WRITE);
        f.oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from (select"
                    + " consumption_hash_v1('COMMAND',?) h)x",
                definition.id().value(),
                request,
                request);
        PotActorAuthority actor =
                new PotActorAuthority() {
                    @Override
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(actorUserId));
                    }

                    @Override
                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var commands =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(f.primary, f.sourceOwner),
                        new JdbcPotCommandObservation(f.primary),
                        actor,
                        new JdbcPotHistoryAdapter(f.primary),
                        new JdbcPotRevisionAdapter(f.primary),
                        Clock.systemUTC());
        var engine =
                new ConsumptionEngine(
                        definition,
                        f.sourceOwner,
                        new JdbcConsumptionSlotStore(f.primary),
                        commands,
                        new JdbcPartitionOwnershipStore(f.primary),
                        f.read.settings,
                        new WorkerCapacity(4, Map.of(definition.id(), 4)),
                        c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        int partition = RoutingV1.partition(RoutingV1.hash(definition.family(), request), 4);
        var lease = f.primaryLease(engine, definition, partition);
        var result = engine.execute(engine.acquire(lease, List.of(request)).claims().getFirst());
        assertEquals(
                ConsumptionProtocol.Status.COMMITTED, result.status(), () -> String.valueOf(result));
        return f.oracle.queryForObject(
                "select event_id from v3_pot_event where request_id=?", UUID.class, request);
    }
}
