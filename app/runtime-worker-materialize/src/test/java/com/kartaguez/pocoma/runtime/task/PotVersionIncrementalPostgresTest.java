package com.kartaguez.pocoma.runtime.task;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.MicrometerPotVersionObservation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotVersion;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcPartitionOwnershipStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcSubobjectVersionStore;
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
class PotVersionIncrementalPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(SubobjectPostgresFixture.IMAGE).withDatabaseName("i1_read");

    @org.junit.jupiter.api.BeforeAll
    static void init() {
        SubobjectPostgresFixture.initialize(PG);
    }

    @Test
    void incrementalNMinusMMatchesFullOracleAcrossOutOfOrderConcurrencyAndGlobalEffects()
            throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "potversion-nm", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());
            var sh1 = new ShareholderId(UUID.randomUUID());
            var sh2 = new ShareholderId(UUID.randomUUID());
            var exp1 = new ExpenseId(UUID.randomUUID());
            var exp2 = new ExpenseId(UUID.randomUUID());

            // Execute real WRITE commands producing versions 1..11
            var eventsByVersion = new TreeMap<Long, UUID>();
            eventsByVersion.put(1L, executeWrite(f, creator, potId, 0, "CreatePot", new CreatePot(new Label("Pot v1"))));
            eventsByVersion.put(
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
                                                    sh1, new Name("Alice"), Optional.of(creator), new Weight(new Fraction(1, 1))),
                                            new ShareholderTransitions.Creation(
                                                    sh2, new Name("Bob"), Optional.empty(), new Weight(new Fraction(2, 1)))))));
            var fin1 =
                    new ExpenseFinancials(
                            potId,
                            exp1,
                            new Amount(new Fraction(30, 1)),
                            sh1,
                            Map.of(sh1, Optional.empty(), sh2, Optional.empty()));
            eventsByVersion.put(
                    3L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            2,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp1, new Label("Dinner"), LocalDate.of(2026, 3, 1)),
                                    fin1)));
            // Successive replacements of EXP_1 details (v4 and v5)
            eventsByVersion.put(
                    4L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            3,
                            "UpdateExpenseDetails",
                            new UpdateExpenseDetails(exp1, new Label("Dinner 2"), LocalDate.of(2026, 3, 2))));
            eventsByVersion.put(
                    5L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            4,
                            "UpdateExpenseDetails",
                            new UpdateExpenseDetails(exp1, new Label("Dinner 3"), LocalDate.of(2026, 3, 3))));
            // Update financials (v6)
            var fin1v2 =
                    new ExpenseFinancials(
                            potId,
                            exp1,
                            new Amount(new Fraction(45, 1)),
                            sh1,
                            Map.of(sh1, Optional.empty(), sh2, Optional.of(new Weight(new Fraction(3, 1)))));
            eventsByVersion.put(
                    6L,
                    executeWrite(f, creator, potId, 5, "UpdateExpenseFinancials", new UpdateExpenseFinancials(fin1v2)));
            // Update shareholder weight (v7)
            eventsByVersion.put(
                    7L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            6,
                            "UpdateShareholderWeights",
                            new UpdateShareholderWeights(
                                    List.of(new ShareholderTransitions.WeightIntent(sh2, new Weight(new Fraction(5, 1)))))));
            // Logical deletion of EXP_1 (v8)
            eventsByVersion.put(
                    8L, executeWrite(f, creator, potId, 7, "DeleteExpense", new DeleteExpense(exp1)));
            // Create EXP_2 (v9)
            var fin2 =
                    new ExpenseFinancials(
                            potId,
                            exp2,
                            new Amount(new Fraction(10, 1)),
                            sh2,
                            Map.of(sh1, Optional.empty()));
            eventsByVersion.put(
                    9L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            8,
                            "CreateExpense",
                            new CreateExpense(
                                    new ExpenseDetails(potId, exp2, new Label("Coffee"), LocalDate.of(2026, 3, 4)),
                                    fin2)));
            // Global shareholder effect: detach active attached shareholder sh1 (v10)
            eventsByVersion.put(
                    10L,
                    executeWrite(
                            f,
                            creator,
                            potId,
                            9,
                            "UpdateShareholderDetails",
                            new UpdateShareholderDetails(
                                    List.of(
                                            new ShareholderTransitions.DetailsIntent(
                                                    sh1, new Name("Alice detached"), Optional.empty())))));
            // Logical deletion of Pot (v11)
            eventsByVersion.put(11L, executeWrite(f, creator, potId, 10, "DeletePot", new DeletePot()));

            var source = new JdbcPotVersionSource(f.primary);
            var subobjects = new JdbcSubobjectVersionStore(f.read.source, f.read.tx);
            var versions = new JdbcPotVersionStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var observation = new MicrometerPotVersionObservation(registry, Tags.empty());
            var participant = new MaterializePotVersion(source, subobjects, versions, 64L, observation);

            // Compute exact full oracle for every version 1..11
            var oracleVersions = new TreeMap<Long, PotVersion>();
            for (var entry : eventsByVersion.entrySet()) {
                oracleVersions.put(
                        entry.getKey(),
                        f.sourceOwner.read(c -> source.composition(entry.getValue(), c)));
            }

            // Materialize out-of-order:
            // v1 (FULL: initial), v6 (INCR from K=1, m=5, successive updates!),
            // v3 (INCR from K=1, m=2, even though read_current_pot is 6!),
            // v9 (INCR from K=6, m=3, includes weight update, logical delete of exp1, create exp2),
            // v10 (FULL: global shareholder detach!), v11 (INCR from K=10, m=1, DeletePot)
            materialize(f, participant, eventsByVersion.get(1L));
            materialize(f, participant, eventsByVersion.get(6L));
            assertEquals(6L, f.read.tx.<Long>read(c -> versions.currentVersion(potId.value(), c).orElseThrow()));
            materialize(f, participant, eventsByVersion.get(3L));
            assertEquals(6L, f.read.tx.<Long>read(c -> versions.currentVersion(potId.value(), c).orElseThrow()));
            materialize(f, participant, eventsByVersion.get(9L));
            materialize(f, participant, eventsByVersion.get(10L));
            materialize(f, participant, eventsByVersion.get(11L));

            // Concurrent out-of-order materialization of v4 and v5 (with v3 and v6 already present)
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var f4 =
                        pool.submit(
                                () -> {
                                    start.await();
                                    materialize(f, participant, eventsByVersion.get(4L));
                                    return true;
                                });
                var f5 =
                        pool.submit(
                                () -> {
                                    start.await();
                                    materialize(f, participant, eventsByVersion.get(5L));
                                    return true;
                                });
                start.countDown();
                assertTrue(f4.get(15, TimeUnit.SECONDS));
                assertTrue(f5.get(15, TimeUnit.SECONDS));
            }
            // Remaining versions v2, v7, v8
            materialize(f, participant, eventsByVersion.get(2L));
            materialize(f, participant, eventsByVersion.get(7L));
            materialize(f, participant, eventsByVersion.get(8L));

            // Idempotence: replay v6 and v9
            materialize(f, participant, eventsByVersion.get(6L));
            materialize(f, participant, eventsByVersion.get(9L));

            // Verify EVERY version 1..11 matches the full oracle bit-for-bit + recovery evidence SATISFIED
            for (long v = 1L; v <= 11L; v++) {
                long version = v;
                PotVersion stored =
                        f.read.tx.read(c -> versions.read(potId.value(), version, c).orElseThrow());
                assertEquals(oracleVersions.get(v), stored, "PotVersion mismatch at v=" + v);
                var expectedSubobjects =
                        f.sourceOwner.read(
                                c -> source.contents(oracleVersions.get(version), oracleVersions.get(version).keys(), c));
                var actualSubobjects =
                        f.read.tx.read(c -> subobjects.readAll(oracleVersions.get(version).keys(), c));
                assertEquals(expectedSubobjects.size(), actualSubobjects.size(), "Subobjects count mismatch at v=" + v);
                for (var expected : expectedSubobjects) {
                    assertEquals(expected, actualSubobjects.get(expected.key()), "Subobject mismatch at v=" + v);
                }
                var recovery =
                        participant.prepareRecovery(eventsByVersion.get(v), prepReads(f));
                var evidence = f.read.tx.read(recovery::observe);
                assertEquals(ConsumptionParticipant.EffectEvidence.SATISFIED, evidence, "Recovery evidence at v=" + v);
            }

            // Verify Micrometer metrics recorded hits, distance, global fallback, and initial version
            assertTrue(
                    registry.get("pocoma.v3.potversion.materialize.path")
                                    .tag("path", "incremental")
                                    .tag("reason", "incremental_hit")
                                    .counter()
                                    .count()
                            >= 8.0);
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "initial_version")
                            .counter()
                            .count());
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "global_shareholder_effect")
                            .counter()
                            .count());
            assertTrue(
                    registry.get("pocoma.v3.potversion.reference.distance").summary().max() >= 5.0);
        }
    }

    @Test
    void maxDeltaDistanceFallbackTriggersFullPathWhenKIsTooFar() throws Exception {
        try (var f = new InterAuthorityPostgresFixture(PG, "potversion-nm-dist", true)) {
            var potId = new PotId(UUID.randomUUID());
            var creator = new UserId(UUID.randomUUID());

            UUID e1 = executeWrite(f, creator, potId, 0, "CreatePot", new CreatePot(new Label("v1")));
            executeWrite(f, creator, potId, 1, "UpdatePotDetails", new UpdatePotDetails(new Label("v2")));
            executeWrite(f, creator, potId, 2, "UpdatePotDetails", new UpdatePotDetails(new Label("v3")));
            UUID e4 = executeWrite(f, creator, potId, 3, "UpdatePotDetails", new UpdatePotDetails(new Label("v4")));

            var source = new JdbcPotVersionSource(f.primary);
            var subobjects = new JdbcSubobjectVersionStore(f.read.source, f.read.tx);
            var versions = new JdbcPotVersionStore(f.read.source, f.read.tx);
            var registry = new SimpleMeterRegistry();
            var obs = new MicrometerPotVersionObservation(registry, Tags.empty());
            // maxDeltaDistance = 2: when only v1 exists, v4 has distance 3 > 2 -> NO_REFERENCE -> FULL
            var participant = new MaterializePotVersion(source, subobjects, versions, 2L, obs);

            materialize(f, participant, e1);
            materialize(f, participant, e4);

            PotVersion oracle4 = f.sourceOwner.read(c -> source.composition(e4, c));
            PotVersion stored4 = f.read.tx.read(c -> versions.read(potId.value(), 4L, c).orElseThrow());
            assertEquals(oracle4, stored4);
            assertEquals(
                    1.0,
                    registry.get("pocoma.v3.potversion.materialize.path")
                            .tag("path", "full")
                            .tag("reason", "no_reference")
                            .counter()
                            .count());
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
            InterAuthorityPostgresFixture f, MaterializePotVersion participant, UUID eventId) {
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
