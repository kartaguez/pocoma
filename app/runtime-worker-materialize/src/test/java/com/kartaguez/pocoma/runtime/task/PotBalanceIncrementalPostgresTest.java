package com.kartaguez.pocoma.runtime.task;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.MicrometerPotBalanceObservation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.pot.PotBalanceCalculator;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotBalance;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcPartitionOwnershipStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;
import com.kartaguez.pocoma.infra.postgres.source.JdbcPotBalanceSource;
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
class PotBalanceIncrementalPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(SubobjectPostgresFixture.IMAGE).withDatabaseName("i1_read");

    private static final PotBalanceCalculator ORACLE = new PotBalanceCalculator();

    @org.junit.jupiter.api.BeforeAll
    static void init() {
        SubobjectPostgresFixture.initialize(PG);
    }

    @Test
    void incrementalNMinusMMatchesOracleAcrossOutOfOrderConcurrencyWeightUpdatesAndDeleteShareholder()
            throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "potbalance-nm", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var sh3 = new ShareholderId(UUID.randomUUID());
            var sh4 = new ShareholderId(UUID.randomUUID());
            var sh5 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var exp2 = new ExpenseId(UUID.randomUUID());
            var exp3 = new ExpenseId(UUID.randomUUID());
            var exp4 = new ExpenseId(UUID.randomUUID());

            var events = new TreeMap<Long, UUID>();
            // v1: CreatePot
            events.put(1L, executeWrite(f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Pot Balance N-m"))));
            // v2: AddShareholders (sh1=1, sh2=2, sh3=3, sh5=1 unused for D4 later)
            events.put(
                    2L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            1,
                            "AddShareholders",
                            new AddShareholders(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1, new Name("Alice"), Optional.of(creator), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("Bob"), Optional.empty(), w(2, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh3, new Name("Charlie"), Optional.empty(), w(3, 1)),
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
                                    new ExpenseDetails(potId, exp1, new Label("Dinner"), LocalDate.of(2026, 3, 1)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(60, 1)),
                                            sh1,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.empty(), sh3, Optional.empty())))));
            // v4: CreateExpense exp2 (mixed: sh1 default, sh2 specific 5/2)
            events.put(
                    4L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp2, new Label("Taxi"), LocalDate.of(2026, 3, 2)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp2,
                                            new Amount(new Fraction(45, 1)),
                                            sh2,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.of(w(5, 2)))))));
            // v5: CreateExpense exp3 (100% specific weights -> absorbed into fixedNet)
            events.put(
                    5L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            4,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp3, new Label("Tickets"), LocalDate.of(2026, 3, 3)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp3,
                                            new Amount(new Fraction(100, 1)),
                                            sh3,
                                            Map.of(sh1, Optional.of(w(1, 1)), sh3, Optional.of(w(3, 1)))))));
            // v6: UpdateExpenseFinancials exp1 (change amount, payer, participants)
            events.put(
                    6L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            5,
                            "UpdateExpenseFinancials",
                            new UpdateExpenseFinancials(
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(90, 1)),
                                            sh2,
                                            Map.of(sh2, Optional.empty(), sh3, Optional.empty())))));
            // v7: UpdateExpenseDetails (non-financial -> 0 changed expenses)
            events.put(
                    7L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            6,
                            "UpdateExpenseDetails",
                            new UpdateExpenseDetails(exp1, new Label("Dinner updated"), LocalDate.of(2026, 3, 4))));
            // v8: UpdateShareholderWeights (retroactive weight change -> 0 expenses read!)
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
            // v9: DeleteExpense exp1 (logical delete)
            events.put(
                    9L,
                    executeWrite(f, creator, potId, 8, "DeleteExpense", new DeleteExpense(exp1)));
            // v10: AddShareholders sh4 (D1 re-indexing!)
            events.put(
                    10L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            9,
                            "AddShareholders",
                            new AddShareholders(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh4, new Name("Dave"), Optional.empty(), w(4, 1))))));
            // v11: CreateExpense exp4 referencing sh4
            events.put(
                    11L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            10,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp4, new Label("Hotel"), LocalDate.of(2026, 3, 5)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp4,
                                            new Amount(new Fraction(200, 1)),
                                            sh4,
                                            Map.of(sh1, Optional.empty(), sh4, Optional.empty())))));
            // v12: DeleteShareholder sh5 (D4 fallback!)
            events.put(
                    12L,
                    executeWrite(f, creator, potId, 11, "DeleteShareholder", new DeleteShareholder(sh5)));
            // v13: UpdateShareholderWeights after D4 fallback (incremental resumes from v12 aggregate!)
            events.put(
                    13L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            12,
                            "UpdateShareholderWeights",
                            new UpdateShareholderWeights(
                                    List.of(new ShareholderTransitions.WeightIntent(sh4, w(3, 2))))));
            // v14: DeletePot (terminal D -> no balance, no aggregate)
            events.put(14L, executeWrite(f, creator, potId, 13, "DeletePot", new DeletePot()));

            var source = new JdbcPotBalanceSource(f.primary);
            var store = new JdbcPotBalanceStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var obs = new MicrometerPotBalanceObservation(registry, Tags.empty());
            var participant =
                    new MaterializePotBalance(
                            source, store, MaterializePotBalance.Settings.DEFAULT, obs);

            // Out-of-order materialization: v1, v5 (N-K=4), v3 (N-K=2), v8 (N-K=3), v6 (N-K=1)
            materialize(f, participant, events.get(1L));
            materialize(f, participant, events.get(5L));
            materialize(f, participant, events.get(3L));
            materialize(f, participant, events.get(8L));
            materialize(f, participant, events.get(6L));

            // Concurrent materialization of v4 and v7
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var start = new CountDownLatch(1);
                var f4 =
                        pool.submit(
                                () -> {
                                    start.await();
                                    materialize(f, participant, events.get(4L));
                                    return true;
                                });
                var f7 =
                        pool.submit(
                                () -> {
                                    start.await();
                                    materialize(f, participant, events.get(7L));
                                    return true;
                                });
                start.countDown();
                assertTrue(f4.get(20, TimeUnit.SECONDS));
                assertTrue(f7.get(20, TimeUnit.SECONDS));
            }

            // Remaining versions out of order: v2, v11, v9, v10, v12 (D4), v13 (incremental after D4), v14 (terminal D)
            for (long v : new long[] {2L, 11L, 9L, 10L, 12L, 13L, 14L}) {
                materialize(f, participant, events.get(v));
            }

            // Idempotent replay of v8
            materialize(f, participant, events.get(8L));

            // Strict exact rational comparison against full PotBalanceCalculator oracle for v1..v14
            for (long v = 1L; v <= 14L; v++) {
                long version = v;
                var state = f.sourceOwner.read(c -> source.load(events.get(version), c));
                var expected = oracleBalance(state);
                var actual = f.read.tx.read(c -> store.read(potId.value(), version, c));
                assertEquals(expected, actual, "Balance divergence at version " + version);
                var recovery = participant.prepareRecovery(events.get(version), prepReads(f));
                assertEquals(
                        ConsumptionParticipant.EffectEvidence.SATISFIED,
                        f.read.tx.read(recovery::observe),
                        "Recovery evidence mismatch at version " + version);
            }

            // Aggregates present for v1..v13, absent for terminal v14
            assertEquals(
                    13,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=?",
                            Integer.class,
                            potId.value()));
            assertEquals(
                    0,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=? and pot_version=14",
                            Integer.class,
                            potId.value()));

            // Verify telemetry counters
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "initial_version")
                            .counter()
                            .count());
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "shareholder_deleted")
                            .counter()
                            .count());
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "terminal_deleted")
                            .counter()
                            .count());
            assertTrue(
                    registry.get("pocoma.v3.potbalance.materialize.path")
                                    .tag("path", "incremental")
                                    .tag("reason", "incremental_hit")
                                    .counter()
                                    .count()
                            >= 11.0);
            assertTrue(
                    registry.get("pocoma.v3.potbalance.reference.distance").summary().max() >= 4.0);
        }
    }

    @Test
    void weightUpdateReadsZeroExpensesAndBucketCapSkipsAggregatePersistence() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "potbalance-nm-caps", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var sh3 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var exp2 = new ExpenseId(UUID.randomUUID());

            UUID e1 = executeWrite(f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Caps")));
            UUID e2 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            1,
                            "AddShareholders",
                            new AddShareholders(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1, new Name("A"), Optional.of(creator), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("B"), Optional.empty(), w(1, 1)),
                                            new ShareholderTransitions.Creation(
                                                    sh3, new Name("C"), Optional.empty(), w(1, 1)))));
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
                                            new Amount(new Fraction(30, 1)),
                                            sh1,
                                            Map.of(sh1, Optional.empty(), sh2, Optional.empty()))));
            UUID e4 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "UpdateShareholderWeights",
                            new UpdateShareholderWeights(
                                    List.of(new ShareholderTransitions.WeightIntent(sh1, w(3, 1)))));
            UUID e5 =
                    executeWrite(
                            f,
                            creator,
                            potId,
                            4,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp2, new Label("E2"), LocalDate.of(2026, 3, 2)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp2,
                                            new Amount(new Fraction(50, 1)),
                                            sh2,
                                            Map.of(sh2, Optional.empty(), sh3, Optional.empty()))));

            var source = new JdbcPotBalanceSource(f.primary);
            var store = new JdbcPotBalanceStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var obs = new MicrometerPotBalanceObservation(registry, Tags.empty());
            // Cap buckets at 1: v1..v4 have <= 1 bucket, v5 has 2 buckets -> BUCKET_CAP!
            var participant =
                    new MaterializePotBalance(
                            source,
                            store,
                            new MaterializePotBalance.Settings(64, 64, 1, 10_000),
                            obs);

            materialize(f, participant, e1);
            materialize(f, participant, e2);
            materialize(f, participant, e3);
            double expensesBeforeWeightUpdate =
                    registry.get("pocoma.v3.potbalance.delta.expenses").summary().totalAmount();
            materialize(f, participant, e4);
            double expensesAfterWeightUpdate =
                    registry.get("pocoma.v3.potbalance.delta.expenses").summary().totalAmount();
            assertEquals(
                    expensesBeforeWeightUpdate,
                    expensesAfterWeightUpdate,
                    "UpdateShareholderWeights must read 0 changed expenses on incremental path");

            materialize(f, participant, e5);
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potbalance.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "bucket_cap")
                            .counter()
                            .count());
            // Balance at v5 is stored and exact, aggregate at v5 is NOT stored
            var state5 = f.sourceOwner.read(c -> source.load(e5, c));
            assertEquals(
                    oracleBalance(state5),
                    f.read.tx.read(c -> store.read(potId.value(), 5L, c)));
            assertEquals(
                    0,
                    f.read.oracle.queryForObject(
                            "select count(*) from read_pot_balance_aggregate where pot_id=? and pot_version=5",
                            Integer.class,
                            potId.value()));
        }
    }

    private static Weight w(long n, long d) {
        return new Weight(new Fraction(n, d));
    }

    private static Optional<PotBalance> oracleBalance(
            com.kartaguez.pocoma.domain.pot.PotFinancialState state) {
        return ORACLE.calculate(state)
                .map(
                        b -> {
                            var values = new HashMap<UUID, Fraction>();
                            b.values().forEach((k, v) -> values.put(k.value(), v));
                            return new PotBalance(b.potId().value(), b.version(), values);
                        });
    }

    @Test
    void emptyDeltaReuseProofNoFalsePositivesAndMicrometerObservability() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "potbalance-arb3", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var sh3 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var expZero = new ExpenseId(UUID.randomUUID());
            var expTemp = new ExpenseId(UUID.randomUUID());

            var events = new TreeMap<Long, UUID>();
            // v1: CreatePot
            events.put(1L, executeWrite(f, creator, potId, 0, "CreatePot", new CreatePot(new Label("ARB-3 Pot"))));
            // v2: AddShareholders (sh1=1, sh2=2, sh3=1)
            events.put(2L, executeWrite(f, creator, potId, 1, "AddShareholders",
                    new AddShareholders(List.of(
                            new ShareholderTransitions.Creation(sh1, new Name("Alice"), Optional.of(creator), w(1, 1)),
                            new ShareholderTransitions.Creation(sh2, new Name("Bob"), Optional.empty(), w(2, 1)),
                            new ShareholderTransitions.Creation(sh3, new Name("Carol"), Optional.empty(), w(1, 1))))));
            // v3: CreateExpense exp1 (60)
            events.put(3L, executeWrite(f, creator, potId, 2, "CreateExpense",
                    new CreateExpense(
                            new ExpenseDetails(potId, exp1, new Label("Lunch"), LocalDate.of(2026, 4, 1)),
                            new ExpenseFinancials(potId, exp1, new Amount(new Fraction(60, 1)), sh1,
                                    Map.of(sh1, Optional.empty(), sh2, Optional.empty())))));
            // v4: UpdatePotDetails (non-financial -> eligible for EMPTY_DELTA_REUSE)
            events.put(4L, executeWrite(f, creator, potId, 3, "UpdatePotDetails",
                    new UpdatePotDetails(new Label("ARB-3 Renamed"))));
            // v5: UpdateShareholderDetails (non-financial -> eligible for EMPTY_DELTA_REUSE, N-m from v3 or v4)
            events.put(5L, executeWrite(f, creator, potId, 4, "UpdateShareholderDetails",
                    new UpdateShareholderDetails(List.of(
                            new ShareholderTransitions.DetailsIntent(sh2, new Name("Bob Renamed"), Optional.empty())))));
            // v6: UpdateExpenseDetails (non-financial -> eligible for EMPTY_DELTA_REUSE)
            events.put(6L, executeWrite(f, creator, potId, 5, "UpdateExpenseDetails",
                    new UpdateExpenseDetails(exp1, new Label("Lunch Updated"), LocalDate.of(2026, 4, 2))));
            // v7: Weight changed (1 -> 5) then v8: Weight reverted (5 -> 1) -> MUST NOT trigger EMPTY_DELTA_REUSE across (6, 8]!
            events.put(7L, executeWrite(f, creator, potId, 6, "UpdateShareholderWeights",
                    new UpdateShareholderWeights(List.of(new ShareholderTransitions.WeightIntent(sh1, w(5, 1))))));
            events.put(8L, executeWrite(f, creator, potId, 7, "UpdateShareholderWeights",
                    new UpdateShareholderWeights(List.of(new ShareholderTransitions.WeightIntent(sh1, w(1, 1))))));
            // v9: Create zero-amount expense -> MUST NOT trigger EMPTY_DELTA_REUSE (changedExpenseCount > 0), MUST deduplicate in store!
            events.put(9L, executeWrite(f, creator, potId, 8, "CreateExpense",
                    new CreateExpense(
                            new ExpenseDetails(potId, expZero, new Label("Zero"), LocalDate.of(2026, 4, 3)),
                            new ExpenseFinancials(potId, expZero, new Amount(Fraction.ZERO), sh1,
                                    Map.of(sh1, Optional.empty(), sh2, Optional.empty())))));
            // v10: Create temp expense then v11: Delete temp expense -> MUST NOT trigger EMPTY_DELTA_REUSE across (9, 11]!
            events.put(10L, executeWrite(f, creator, potId, 9, "CreateExpense",
                    new CreateExpense(
                            new ExpenseDetails(potId, expTemp, new Label("Temp"), LocalDate.of(2026, 4, 4)),
                            new ExpenseFinancials(potId, expTemp, new Amount(new Fraction(30, 1)), sh2,
                                    Map.of(sh1, Optional.empty(), sh2, Optional.empty())))));
            events.put(11L, executeWrite(f, creator, potId, 10, "DeleteExpense", new DeleteExpense(expTemp)));
            // v12: DeleteShareholder sh3 (unused) -> MUST trigger FULL/SHAREHOLDER_DELETED, balance reused, aggregate inserted!
            events.put(12L, executeWrite(f, creator, potId, 11, "DeleteShareholder", new DeleteShareholder(sh3)));

            var source = new JdbcPotBalanceSource(f.primary);
            var store = new JdbcPotBalanceStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var observation = new MicrometerPotBalanceObservation(registry, Tags.of("test", "arb3"));
            var participant = new MaterializePotBalance(source, store, MaterializePotBalance.Settings.DEFAULT, observation);

            // Materialize v1, v2, v3 (baseline financial state)
            materialize(f, participant, events.get(1L));
            materialize(f, participant, events.get(2L));
            materialize(f, participant, events.get(3L));
            int balContentsAfterV3 = f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class);
            int aggContentsAfterV3 = f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class);

            // 1. N-m EMPTY_DELTA_REUSE: materialize v6 directly from v3 (distance = 3, covering UpdatePotDetails + UpdateShareholderDetails + UpdateExpenseDetails)
            materialize(f, participant, events.get(6L));
            assertEquals(1.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count());
            // Consecutive EMPTY_DELTA_REUSE: v4 and v5
            materialize(f, participant, events.get(4L));
            materialize(f, participant, events.get(5L));
            assertEquals(3.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count());
            // Zero new physical content rows created for v4, v5, v6!
            assertEquals(balContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
            assertEquals(aggContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

            // 2. Weight modified (v7) then reverted (v8): materialize v8 directly from v6 (skipping v7)
            // MUST NOT trigger EMPTY_DELTA_REUSE! Must take INCREMENTAL and deduplicate physically in store!
            double reuseBefore = registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count();
            double incBefore = registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "incremental", "reason", "incremental_hit").count();
            materialize(f, participant, events.get(8L));
            assertEquals(reuseBefore, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count(), "Reverted weight in (6,8] must NOT trigger EMPTY_DELTA_REUSE");
            assertEquals(incBefore + 1.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "incremental", "reason", "incremental_hit").count());
            assertEquals(balContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class), "Physical dedup reuses balance");
            assertEquals(aggContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class), "Physical dedup reuses aggregate");

            // 3. Zero-amount expense (v9): MUST NOT trigger EMPTY_DELTA_REUSE, MUST deduplicate in store!
            materialize(f, participant, events.get(9L));
            assertEquals(reuseBefore, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count(), "Zero-amount expense must NOT trigger EMPTY_DELTA_REUSE");
            assertEquals(incBefore + 2.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "incremental", "reason", "incremental_hit").count());
            assertEquals(balContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
            assertEquals(aggContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

            // 4. Expense created (v10) then deleted (v11): materialize v11 directly from v9 (skipping v10)
            // MUST NOT trigger EMPTY_DELTA_REUSE! Must take INCREMENTAL and deduplicate physically!
            materialize(f, participant, events.get(11L));
            assertEquals(reuseBefore, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "reuse", "reason", "empty_delta_reuse").count(), "Created+deleted expense in (9,11] must NOT trigger EMPTY_DELTA_REUSE");
            assertEquals(incBefore + 3.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "incremental", "reason", "incremental_hit").count());
            assertEquals(balContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
            assertEquals(aggContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

            // 5. DeleteShareholder (v12): FULL/SHAREHOLDER_DELETED; balance reused, aggregate inserted!
            materialize(f, participant, events.get(12L));
            assertEquals(1.0, registry.counter("pocoma.v3.potbalance.materialize.path",
                    "test", "arb3", "path", "full", "reason", "shareholder_deleted").count());
            assertEquals(balContentsAfterV3, f.read.oracle.queryForObject("select count(*) from read_pot_balance_content", Integer.class));
            assertEquals(aggContentsAfterV3 + 1, f.read.oracle.queryForObject("select count(*) from read_pot_balance_aggregate_content", Integer.class));

            // Materialize remaining out-of-order versions (v7, v10) and replay v6 idempotently
            materialize(f, participant, events.get(7L));
            materialize(f, participant, events.get(10L));
            materialize(f, participant, events.get(6L));

            // Verify Micrometer content write & encode metrics are populated
            assertTrue(registry.counter("pocoma.v3.potbalance.content.write",
                    "test", "arb3", "target", "balance", "outcome", "inserted").count() > 0);
            assertTrue(registry.counter("pocoma.v3.potbalance.content.write",
                    "test", "arb3", "target", "balance", "outcome", "reused").count() >= 7.0);
            assertTrue(registry.counter("pocoma.v3.potbalance.content.write",
                    "test", "arb3", "target", "aggregate", "outcome", "reused").count() >= 6.0);
            assertTrue(registry.timer("pocoma.v3.potbalance.encode.duration",
                    "test", "arb3", "target", "balance").count() > 0);
            assertTrue(registry.timer("pocoma.v3.potbalance.persist.duration",
                    "test", "arb3", "target", "balance").count() > 0);

            // Exact equivalence against full PotBalanceCalculator oracle for all 12 versions!
            for (long v = 1L; v <= 12L; v++) {
                long version = v;
                UUID eventId = events.get(version);
                var state = f.sourceOwner.read(c -> source.load(eventId, c));
                var expected = ORACLE.calculate(state).map(b -> {
                    var values = new HashMap<UUID, Fraction>(b.values().size() * 2);
                    b.values().forEach((k, val) -> values.put(k.value(), val));
                    return new PotBalance(b.potId().value(), b.version(), values);
                });
                var actual = f.read.tx.read(c -> store.read(potId.value(), version, c));
                assertEquals(expected, actual, "Oracle mismatch at v=" + version);
                var recovery = participant.prepareRecovery(eventId, prepReads(f));
                assertEquals(ConsumptionParticipant.EffectEvidence.SATISFIED,
                        f.read.tx.read(recovery::observe), "Recovery mismatch at v=" + version);
            }
        }
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

    private static void materialize(
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
