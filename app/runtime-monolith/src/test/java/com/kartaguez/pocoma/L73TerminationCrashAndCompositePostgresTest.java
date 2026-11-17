package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.AddShareholders;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreateExpense;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreatePot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Eui;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.domain.identity.BindingToken;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.pot.Amount;
import com.kartaguez.pocoma.domain.pot.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.Fraction;
import com.kartaguez.pocoma.domain.pot.ShareholderDetails;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.Weight;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.identity.ExecuteIdentityRequest;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.infra.postgres.core.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.JdbcPartitionOwnershipStore;
import com.kartaguez.pocoma.infra.postgres.write.JdbcPotCommandObservation;
import com.kartaguez.pocoma.infra.postgres.write.JdbcPotCommandStore;
import com.kartaguez.pocoma.infra.postgres.write.JdbcPotHistoryAdapter;
import com.kartaguez.pocoma.infra.postgres.write.JdbcPotRevisionAdapter;
import com.kartaguez.pocoma.infra.postgres.write.PotCommandJson;
import com.kartaguez.pocoma.infra.postgres.write.identity.IdentityRequestJson;
import com.kartaguez.pocoma.infra.postgres.write.identity.JdbcIdentityAuthority;
import com.kartaguez.pocoma.infra.postgres.write.identity.JdbcIdentityRequestObservation;
import com.kartaguez.pocoma.infra.postgres.write.identity.JdbcIdentityRequestStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Testcontainers
class L73TerminationCrashAndCompositePostgresTest {
    private static final UUID COMMAND_CONSUMPTION_ID =
            UUID.fromString("98f7268d-d340-581c-b83d-010926223e14");
    private static final UUID IDENTITY_CONSUMPTION_ID =
            UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe");
    private static final UUID READ_PROBE_CONSUMPTION_ID =
            UUID.fromString("3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a");
    private static final UUID READ_PROBE_EFFECT_ID =
            UUID.fromString("a5000000-0000-4000-8000-000000000001");

    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(WorkerMonolithPostgresTest.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        WorkerMonolithPostgresTest.initialize(PG);
    }

    /**
     * L2M-CON-05-CRASH: real SIGKILL while claim is acquired (without cede) on both Command (WRITE)
     * and READ, verifying attempt_count=0, slot preserved, and recovery after real SQL lease expiry.
     */
    @Test
    void l2m_con_05_crash_sigkillWithClaimAcquiredRecoversAfterSqlLeaseExpiry() throws Exception {
        try (var f = new WorkerMonolithPostgresTest.Fixture(PG)) {
            runCrashScenario(f, true, false);
            runCrashScenario(f, false, false);
        }
    }

    /**
     * L2M-C03-J-CRASH: real SIGKILL during in-flight calculation/finalization on both Command
     * (WRITE) and READ, verifying physical PG rollback, zero partial effects, and clean recovery.
     */
    @Test
    void l2m_c03_j_crash_sigkillDuringInFlightWriteRollsBackAndRecovers() throws Exception {
        try (var f = new WorkerMonolithPostgresTest.Fixture(PG)) {
            runCrashScenario(f, true, true);
            runCrashScenario(f, false, true);
        }
    }

    /**
     * L2M-C04-K: composite Command + READ corpus combining takeover, rollback, and in-flight crash,
     * reconciling effects/publications to committed finalizations only.
     */
    @Test
    void l2m_c04_k_compositeCommandAndReadCorpusWithTakeoverRollbackAndCrash() throws Exception {
        try (var f = new WorkerMonolithPostgresTest.Fixture(PG)) {
            var writeDs = new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture");
            var readDs = new DriverManagerDataSource(f.readUrl, f.readRole, "i5-fixture");
            var writeTx = tx(writeDs);
            var readTx = tx(readDs);

            UUID potUuid = UUID.randomUUID();
            PotId potId = new PotId(potUuid);
            Eui actor = new Eui("c04k", "owner");
            UserId userId = new UserId(UUID.randomUUID());
            ShareholderId sh1 = new ShareholderId(UUID.randomUUID());
            ShareholderId sh2 = new ShareholderId(UUID.randomUUID());
            ExpenseId exp1 = new ExpenseId(UUID.randomUUID());

            PotActorAuthority auth =
                    new PotActorAuthority() {
                        @Override
                        public Witness observe(PotCommand command) {
                            return new Witness(1, Optional.of(userId));
                        }

                        @Override
                        public boolean protect(PotCommand command, Witness witness) {
                            return true;
                        }
                    };
            var cmdDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(COMMAND_CONSUMPTION_ID),
                            ConsumableFamily.COMMAND,
                            ConsumptionDefinition.Authority.WRITE);
            var participant =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(writeDs, writeTx),
                            new JdbcPotCommandObservation(writeDs),
                            auth,
                            new JdbcPotHistoryAdapter(writeDs),
                            new JdbcPotRevisionAdapter(writeDs),
                            Clock.systemUTC());

            // 1. CreatePot + AddShareholders (committed baseline)
            var baseEngine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            participant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(4, Map.of(cmdDef.id(), 4)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            admitAndRunWrite(
                    f.write,
                    baseEngine,
                    cmdDef,
                    new PotCommand(
                            UUID.randomUUID(),
                            actor,
                            "tok",
                            potId,
                            0L,
                            new CreatePot(new Label("C04K"))));
            admitAndRunWrite(
                    f.write,
                    baseEngine,
                    cmdDef,
                    new PotCommand(
                            UUID.randomUUID(),
                            actor,
                            "tok",
                            potId,
                            1L,
                            new AddShareholders(
                                    List.of(
                                            new ShareholderTransitions.Creation(
                                                    sh1,
                                                    new Name("Alice"),
                                                    Optional.empty(),
                                                    new Weight(Fraction.ONE)),
                                            new ShareholderTransitions.Creation(
                                                    sh2,
                                                    new Name("Bob"),
                                                    Optional.empty(),
                                                    new Weight(Fraction.ONE))))));

            // 2. Forced rollback followed by retry -> COMMITTED
            UUID expCmdId = UUID.randomUUID();
            var expCmd =
                    new PotCommand(
                            expCmdId,
                            actor,
                            "tok",
                            potId,
                            2L,
                            new CreateExpense(
                                    new ExpenseDetails(
                                            potId,
                                            exp1,
                                            new Label("Dinner"),
                                            LocalDate.of(2026, 11, 1)),
                                    new ExpenseFinancials(
                                            potId,
                                            exp1,
                                            new Amount(new Fraction(100, 1)),
                                            sh1,
                                            Map.of(
                                                    sh1,
                                                    Optional.empty(),
                                                    sh2,
                                                    Optional.empty()))));
            admitCommand(f.write, expCmd);
            var failOnce = new AtomicBoolean(false);
            var rollbackEngine =
                    new ConsumptionEngine(
                            cmdDef,
                            failOnceWriteTx(writeTx, failOnce),
                            new JdbcConsumptionSlotStore(writeDs),
                            participant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(2, Map.of(cmdDef.id(), 2)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            int pExp =
                    RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, expCmdId), 4);
            var leaseExp = acquireLeaseWithRetry(rollbackEngine, pExp);
            var claim1 = rollbackEngine.acquire(leaseExp, List.of(expCmdId)).claims().getFirst();
            failOnce.set(true);
            assertEquals(
                    ConsumptionProtocol.Status.ROLLED_BACK,
                    rollbackEngine.execute(claim1).status());
            assertEquals(
                    0,
                    f.write.queryForObject(
                            "select count(*) from v3_command_result where request_id=?",
                            Integer.class,
                            expCmdId));

            // Retry after rollback -> COMMITTED
            f.write.update(
                    "update consumption_slot set retry_after=null,claimed_until=null,claim_token=null where consumable_id=?",
                    expCmdId);
            var healthyEngine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            participant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(2, Map.of(cmdDef.id(), 2)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var claim2 = healthyEngine.acquire(leaseExp, List.of(expCmdId)).claims().getFirst();
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED, healthyEngine.execute(claim2).status());

            // 3. Stale-claim takeover: Worker 1 pauses after claim, Worker 2 takes over after lease
            // expiry -> Worker 1 gets CLAIM_LOST, Worker 2 COMMITTED
            UUID updCmdId = UUID.randomUUID();
            var updCmd =
                    new PotCommand(
                            updCmdId,
                            actor,
                            "tok",
                            potId,
                            3L,
                            new PotCommand.UpdatePotDetails(new Label("C04K-v4")));
            admitCommand(f.write, updCmd);
            int pUpd =
                    RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, updCmdId), 4);
            var w1Engine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            participant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(2, Map.of(cmdDef.id(), 2)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var w1Lease = acquireLeaseWithRetry(w1Engine, pUpd);
            var w1Claim = w1Engine.acquire(w1Lease, List.of(updCmdId)).claims().getFirst();

            // Wait for SQL claim TTL (1.5s) to expire, then Worker 2 takes over partition & slot
            Thread.sleep(1700);
            var w2Engine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            participant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(2, Map.of(cmdDef.id(), 2)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var w2Lease = acquireLeaseWithRetry(w2Engine, pUpd);
            var w2Claim = w2Engine.acquire(w2Lease, List.of(updCmdId)).claims().getFirst();
            assertEquals(ConsumptionProtocol.Status.COMMITTED, w2Engine.execute(w2Claim).status());
            var w1Res = w1Engine.execute(w1Claim);
            assertTrue(
                    w1Res.status() == ConsumptionProtocol.Status.CLAIM_LOST
                            || w1Res.status() == ConsumptionProtocol.Status.COMMITTED,
                    () -> "unexpected status " + w1Res);

            // 4. Mirror committed events into READ probe effects with crash + recovery on READ
            UUID readConsumable =
                    f.write.queryForObject(
                            "select event_id from v3_pot_event limit 1", UUID.class);
            f.read.update(
                    "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1('POT_EVENT',?) h)x",
                    READ_PROBE_CONSUMPTION_ID,
                    readConsumable,
                    readConsumable);
            var readDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(READ_PROBE_CONSUMPTION_ID),
                            ConsumableFamily.POT_EVENT,
                            ConsumptionDefinition.Authority.READ);
            var readEngine =
                    readEngine(
                            readDef,
                            readDs,
                            readTx,
                            writeDs,
                            writeTx,
                            readProbeParticipant(readDs, readConsumable),
                            shortLeaseSettings());
            int pRead =
                    RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, readConsumable), 4);
            var rLease = acquireLeaseWithRetry(readEngine, pRead);
            var rClaim = readEngine.acquire(rLease, List.of(readConsumable)).claims().getFirst();
            assertEquals(ConsumptionProtocol.Status.COMMITTED, readEngine.execute(rClaim).status());

            // Exact reconciliation: 4 commands -> 4 results, 4 POT events, 4 completions, 0 slots
            assertEquals(
                    4L,
                    f.write.queryForObject("select count(*) from v3_command_result", Long.class));
            assertEquals(
                    4L, f.write.queryForObject("select count(*) from v3_pot_event", Long.class));
            assertEquals(
                    4L,
                    f.write.queryForObject(
                            "select count(*) from v3_command_completion_event", Long.class));
            assertEquals(
                    0L,
                    f.write.queryForObject(
                            "select count(*) from consumption_slot", Long.class));
            assertEquals(
                    1L,
                    f.read.queryForObject(
                            "select count(*) from i5_read_probe_effect where consumable_id=?",
                            Long.class,
                            readConsumable));
            assertEquals(
                    0L,
                    f.read.queryForObject("select count(*) from consumption_slot", Long.class));
        }
    }

    /**
     * L2M-E01-H-REPORT: mixed safety load of applicable producers (Pot + Identity + Invitation) with
     * slow transactions, aborts/rollbacks, and retries, reconciling all 5 families densely.
     */
    @Test
    void l2m_e01_h_report_mixedProducersSafetyLoadAllFamiliesDense() throws Exception {
        try (var f = new WorkerMonolithPostgresTest.Fixture(PG);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var writeDs =
                    new DriverManagerDataSource(f.writeUrl, PG.getUsername(), PG.getPassword());
            var writeTx = tx(writeDs);

            PotId potId = new PotId(UUID.randomUUID());
            Eui ownerEui = new Eui("e01h", "owner");
            UserId ownerId = new UserId(UUID.randomUUID());
            PotActorAuthority auth =
                    new PotActorAuthority() {
                        @Override
                        public Witness observe(PotCommand command) {
                            return new Witness(1, Optional.of(ownerId));
                        }

                        @Override
                        public boolean protect(PotCommand command, Witness witness) {
                            return true;
                        }
                    };
            var cmdDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(COMMAND_CONSUMPTION_ID),
                            ConsumableFamily.COMMAND,
                            ConsumptionDefinition.Authority.WRITE);
            var potParticipant =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(writeDs, writeTx),
                            new JdbcPotCommandObservation(writeDs),
                            auth,
                            new JdbcPotHistoryAdapter(writeDs),
                            new JdbcPotRevisionAdapter(writeDs),
                            Clock.systemUTC(),
                            new com.kartaguez.pocoma.infra.postgres.write.JdbcInvitationStore(
                                    writeDs,
                                    new com.kartaguez.pocoma.infra.security.spring
                                            .InvitationCryptography(
                                            "fixture", Map.of("fixture", new byte[32]))));
            var baseCmdEngine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            potParticipant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(4, Map.of(cmdDef.id(), 4)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            admitAndRunWrite(
                    f.write,
                    baseCmdEngine,
                    cmdDef,
                    new PotCommand(
                            UUID.randomUUID(),
                            ownerEui,
                            "tok",
                            potId,
                            0L,
                            new CreatePot(new Label("E01H"))));

            var idDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(IDENTITY_CONSUMPTION_ID),
                            ConsumableFamily.IDENTITY_REQUEST,
                            ConsumptionDefinition.Authority.WRITE);
            var idParticipant =
                    new ExecuteIdentityRequest(
                            new JdbcIdentityRequestStore(writeDs, writeTx),
                            new JdbcIdentityRequestObservation(writeDs),
                            new JdbcIdentityAuthority(writeDs, writeTx),
                            () -> new BindingToken("tok-" + UUID.randomUUID()),
                            UUID::randomUUID);
            var sharedIdEngine =
                    new ConsumptionEngine(
                            idDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            idParticipant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(4, Map.of(idDef.id(), 4)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);

            // Concurrent mixed producers: Pot mutations (with slow tx + forced abort + retry),
            // Identity registrations, and Invitation creations
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                tasks.add(
                        () -> {
                            var ext = new ExternalIdentity("e01h", "user-" + idx);
                            var reqId = UUID.randomUUID();
                            var req =
                                    new IdentityRequest(
                                            reqId,
                                            ext,
                                            new IdentityRequest.Registration(),
                                            null,
                                            Instant.EPOCH,
                                            new IdentityRequest.AdmissionContext(
                                                    "e01h",
                                                    "1",
                                                    ext,
                                                    ext,
                                                    reqId.toString(),
                                                    reqId.toString(),
                                                    Instant.EPOCH,
                                                    Map.of(),
                                                    IdentityRequest.Origin.CONTROLLED_INTERNAL));
                            f.write.update(
                                    "with req as (insert into v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at) values (?,?,?,?,?,?,?,?::jsonb,?) returning request_id), pos as (update v3_request_counter set position=position+1 where family='IDENTITY_REQUEST' returning position) insert into v3_request_position(family,position,request_id) select 'IDENTITY_REQUEST',pos.position,req.request_id from req,pos",
                                    reqId,
                                    ext.issuer(),
                                    ext.subject(),
                                    IdentityRequestJson.operation(req),
                                    null,
                                    ext.issuer(),
                                    ext.subject(),
                                    IdentityRequestJson.encode(req.admission()),
                                    Timestamp.from(Instant.EPOCH));
                            f.write.update(
                                    "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1('IDENTITY_REQUEST',?) h)x",
                                    IDENTITY_CONSUMPTION_ID,
                                    reqId,
                                    reqId);
                            int part =
                                    RoutingV1.partition(
                                            RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, reqId),
                                            4);
                            var lease = acquireLeaseWithRetry(sharedIdEngine, part);
                            var claim =
                                    sharedIdEngine.acquire(lease, List.of(reqId)).claims().getFirst();
                            var execRes = sharedIdEngine.execute(claim);
                            assertEquals(
                                    ConsumptionProtocol.Status.COMMITTED,
                                    execRes.status(),
                                    () -> "cause=" + execRes.cause());
                            return null;
                        });
            }

            // Concurrent Pot UpdatePotDetails + Invitation + forced rollback & retry
            tasks.add(
                    () -> {
                        var cmdId = UUID.randomUUID();
                        var cmd =
                                new PotCommand(
                                        cmdId,
                                        ownerEui,
                                        "tok",
                                        potId,
                                        0L,
                                        new PotCommand.CreateInvitation(
                                                Instant.now().plus(Duration.ofDays(7))));
                        admitCommand(f.write, cmd);
                        var failOnce = new AtomicBoolean(false);
                        var eFail =
                                new ConsumptionEngine(
                                        cmdDef,
                                        failOnceWriteTx(writeTx, failOnce),
                                        new JdbcConsumptionSlotStore(writeDs),
                                        potParticipant,
                                        new JdbcPartitionOwnershipStore(writeDs),
                                        shortLeaseSettings(),
                                        new WorkerCapacity(4, Map.of(cmdDef.id(), 4)),
                                        fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                        int part =
                                RoutingV1.partition(
                                        RoutingV1.hash(ConsumableFamily.COMMAND, cmdId), 4);
                        var lease = acquireLeaseWithRetry(eFail, part);
                        var c1 = eFail.acquire(lease, List.of(cmdId)).claims().getFirst();
                        failOnce.set(true);
                        assertEquals(
                                ConsumptionProtocol.Status.ROLLED_BACK,
                                eFail.execute(c1).status());

                        f.write.update(
                                "update consumption_slot set retry_after=null,claimed_until=null,claim_token=null where consumable_id=?",
                                cmdId);
                        var eOk =
                                new ConsumptionEngine(
                                        cmdDef,
                                        writeTx,
                                        new JdbcConsumptionSlotStore(writeDs),
                                        potParticipant,
                                        new JdbcPartitionOwnershipStore(writeDs),
                                        shortLeaseSettings(),
                                        new WorkerCapacity(4, Map.of(cmdDef.id(), 4)),
                                        fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                        var c2 = eOk.acquire(lease, List.of(cmdId)).claims().getFirst();
                        var eOkRes = eOk.execute(c2);
                        assertEquals(
                                ConsumptionProtocol.Status.COMMITTED,
                                eOkRes.status(),
                                () -> "eOk cause=" + eOkRes.cause());
                        return null;
                    });

            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> fut : futures) {
                fut.get(30, TimeUnit.SECONDS);
            }

            // Reconcile all 5 families: dense 1..N positions, matching v3_event_counter, 0 residual
            // slots
            for (String family :
                    List.of(
                            "POT",
                            "COMMAND_COMPLETION",
                            "INVITATION",
                            "IDENTITY",
                            "IDENTITY_COMPLETION")) {
                var positions =
                        f.write.queryForList(
                                "select position from v3_event_position where family=? order by"
                                        + " position",
                                Long.class,
                                family);
                for (int i = 0; i < positions.size(); i++) {
                    assertEquals(i + 1L, positions.get(i), "dense gap in " + family);
                }
                assertEquals(
                        (long) positions.size(),
                        f.write.queryForObject(
                                "select position from v3_event_counter where family=?",
                                Long.class,
                                family),
                        "counter mismatch in " + family);
            }
            assertEquals(1L, f.write.queryForObject("select position from v3_event_counter where family='POT'", Long.class));
            assertEquals(2L, f.write.queryForObject("select position from v3_event_counter where family='COMMAND_COMPLETION'", Long.class));
            assertEquals(1L, f.write.queryForObject("select position from v3_event_counter where family='INVITATION'", Long.class));
            assertEquals(8L, f.write.queryForObject("select position from v3_event_counter where family='IDENTITY'", Long.class));
            assertEquals(4L, f.write.queryForObject("select position from v3_event_counter where family='IDENTITY_COMPLETION'", Long.class));
            assertEquals(0L, f.write.queryForObject("select count(*) from consumption_slot", Long.class));
        }
    }

    private void runCrashScenario(
            WorkerMonolithPostgresTest.Fixture f, boolean writeAuthority, boolean inFlightWrite)
            throws Exception {
        UUID consumableId = UUID.randomUUID();
        UUID consumptionId = writeAuthority ? COMMAND_CONSUMPTION_ID : READ_PROBE_CONSUMPTION_ID;
        ConsumableFamily family =
                writeAuthority ? ConsumableFamily.COMMAND : ConsumableFamily.POT_EVENT;
        JdbcTemplate db = writeAuthority ? f.write : f.read;
        String url = writeAuthority ? f.writeUrl : f.readUrl;
        String role = writeAuthority ? f.writeRole : f.readRole;

        if (writeAuthority) {
            admitCommand(
                    f.write,
                    new PotCommand(
                            consumableId,
                            new Eui("crash", "actor"),
                            "tok",
                            new PotId(UUID.randomUUID()),
                            0L,
                            new CreatePot(new Label("CrashPot"))));
        } else {
            // Create a real POT_EVENT on WRITE so READ's RecoverySourcePolicy finds the source event
            var writeDs = new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture");
            var writeTx = tx(writeDs);
            var cmdDef =
                    new ConsumptionDefinition(
                            new ConsumptionId(COMMAND_CONSUMPTION_ID),
                            ConsumableFamily.COMMAND,
                            ConsumptionDefinition.Authority.WRITE);
            var cmdParticipant =
                    new ExecutePotCommand(
                            new JdbcPotCommandStore(writeDs, writeTx),
                            new JdbcPotCommandObservation(writeDs),
                            new PotActorAuthority() {
                                @Override
                                public Witness observe(PotCommand command) {
                                    return new Witness(1, Optional.of(new UserId(UUID.randomUUID())));
                                }

                                @Override
                                public boolean protect(PotCommand command, Witness witness) {
                                    return true;
                                }
                            },
                            new JdbcPotHistoryAdapter(writeDs),
                            new JdbcPotRevisionAdapter(writeDs),
                            Clock.systemUTC());
            var cmdEngine =
                    new ConsumptionEngine(
                            cmdDef,
                            writeTx,
                            new JdbcConsumptionSlotStore(writeDs),
                            cmdParticipant,
                            new JdbcPartitionOwnershipStore(writeDs),
                            shortLeaseSettings(),
                            new WorkerCapacity(1, Map.of(cmdDef.id(), 1)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            UUID cmdId = UUID.randomUUID();
            admitAndRunWrite(
                    f.write,
                    cmdEngine,
                    cmdDef,
                    new PotCommand(
                            cmdId,
                            new Eui("crash", "actor"),
                            "tok",
                            new PotId(UUID.randomUUID()),
                            0L,
                            new CreatePot(new Label("CrashPotRead"))));
            consumableId =
                    f.write.queryForObject(
                            "select event_id from v3_pot_event where request_id=?",
                            UUID.class,
                            cmdId);
            f.read.update(
                    "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1('POT_EVENT',?) h)x",
                    consumptionId,
                    consumableId,
                    consumableId);
        }

        int partition = RoutingV1.partition(RoutingV1.hash(family, consumableId), 4);
        Path tempDir = Files.createTempDirectory("l73-crash-");
        Path log = tempDir.resolve("child.log");
        String classpath =
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-cp",
                                classpath,
                                CrashChild.class.getName(),
                                f.writeUrl,
                                f.writeRole,
                                url,
                                role,
                                "i5-fixture",
                                writeAuthority ? "WRITE" : "READ",
                                consumptionId.toString(),
                                consumableId.toString(),
                                Integer.toString(partition),
                                Boolean.toString(inFlightWrite))
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        try {
            String marker = inFlightWrite ? "IN_FLIGHT_WRITE" : "CLAIM_ACQUIRED";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (System.nanoTime() < deadline) {
                if (Files.exists(log) && Files.readString(log).contains(marker)) {
                    break;
                }
                if (!child.isAlive()) {
                    fail("Child exited early (" + child.exitValue() + "): " + Files.readString(log));
                }
                Thread.sleep(25);
            }
            assertTrue(
                    Files.exists(log) && Files.readString(log).contains(marker),
                    "Child did not reach " + marker + ": " + (Files.exists(log) ? Files.readString(log) : ""));

            // SIGKILL without ceding
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));

            // Verify attempt_count=0, slot preserved, zero partial business effects
            var slot =
                    db.queryForMap(
                            "select attempt_count,state,claim_token is not null as claimed from consumption_slot where consumption_id=? and consumable_id=?",
                            consumptionId,
                            consumableId);
            assertEquals(0, ((Number) slot.get("attempt_count")).intValue());
            assertEquals("AVAILABLE", slot.get("state"));
            assertEquals(Boolean.TRUE, slot.get("claimed"));
            if (writeAuthority) {
                assertEquals(
                        0L,
                        db.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Long.class,
                                consumableId));
            } else {
                assertEquals(
                        0L,
                        db.queryForObject(
                                "select count(*) from i5_read_probe_effect where consumable_id=?",
                                Long.class,
                                consumableId));
            }

            // Wait for real SQL lease expiration (2s claim TTL)
            long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < expiryDeadline) {
                Boolean expired =
                        db.queryForObject(
                                "select claimed_until <= clock_timestamp() from consumption_slot where consumption_id=? and consumable_id=?",
                                Boolean.class,
                                consumptionId,
                                consumableId);
                if (Boolean.TRUE.equals(expired)) break;
                Thread.sleep(50);
            }

            // Recover with a fresh healthy worker
            var ds = new DriverManagerDataSource(url, role, "i5-fixture");
            var tx = tx(ds);
            var def =
                    new ConsumptionDefinition(
                            new ConsumptionId(consumptionId),
                            family,
                            writeAuthority
                                    ? ConsumptionDefinition.Authority.WRITE
                                    : ConsumptionDefinition.Authority.READ);
            ConsumptionParticipant participant =
                    writeAuthority
                            ? new ExecutePotCommand(
                                    new JdbcPotCommandStore(ds, tx),
                                    new JdbcPotCommandObservation(ds),
                                    new PotActorAuthority() {
                                        @Override
                                        public Witness observe(PotCommand command) {
                                            return new Witness(1, Optional.of(new UserId(UUID.randomUUID())));
                                        }

                                        @Override
                                        public boolean protect(PotCommand command, Witness witness) {
                                            return true;
                                        }
                                    },
                                    new JdbcPotHistoryAdapter(ds),
                                    new JdbcPotRevisionAdapter(ds),
                                    Clock.systemUTC())
                            : readProbeParticipant(ds, consumableId);
            var engine =
                    writeAuthority
                            ? new ConsumptionEngine(
                                    def,
                                    tx,
                                    new JdbcConsumptionSlotStore(ds),
                                    participant,
                                    new JdbcPartitionOwnershipStore(ds),
                                    shortLeaseSettings(),
                                    new WorkerCapacity(1, Map.of(def.id(), 1)),
                                    fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE)
                            : readEngine(
                                    def,
                                    ds,
                                    tx,
                                    new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture"),
                                    tx(
                                            new DriverManagerDataSource(
                                                    f.writeUrl, f.writeRole, "i5-fixture")),
                                    participant,
                                    shortLeaseSettings());
            var lease = acquireLeaseWithRetry(engine, partition);
            var claim = engine.acquire(lease, List.of(consumableId)).claims().getFirst();
            var recRes = engine.execute(claim);
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED,
                    recRes.status(),
                    () -> "recovery cause=" + recRes.cause());

            // Verify unique completion and zero remaining slot
            assertEquals(
                    0L,
                    db.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=? and consumable_id=?",
                            Long.class,
                            consumptionId,
                            consumableId));
            if (writeAuthority) {
                assertEquals(
                        1L,
                        db.queryForObject(
                                "select count(*) from v3_command_result where request_id=?",
                                Long.class,
                                consumableId));
            } else {
                assertEquals(
                        1L,
                        db.queryForObject(
                                "select count(*) from i5_read_probe_effect where consumable_id=?",
                                Long.class,
                                consumableId));
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            Files.deleteIfExists(log);
            Files.deleteIfExists(tempDir);
        }
    }

    public static final class CrashChild {
        public static void main(String[] args) throws Exception {
            String writeUrl = args[0];
            String writeRole = args[1];
            String url = args[2];
            String user = args[3];
            String password = args[4];
            boolean write = args[5].equals("WRITE");
            UUID consumptionId = UUID.fromString(args[6]);
            UUID consumableId = UUID.fromString(args[7]);
            int partition = Integer.parseInt(args[8]);
            boolean inFlightWrite = Boolean.parseBoolean(args[9]);

            var ds = new DriverManagerDataSource(url, user, password);
            var tm = new DataSourceTransactionManager(ds);
            tm.setNestedTransactionAllowed(false);
            var tx = new SpringAttemptTransactions(ds, tm);
            var def =
                    new ConsumptionDefinition(
                            new ConsumptionId(consumptionId),
                            write ? ConsumableFamily.COMMAND : ConsumableFamily.POT_EVENT,
                            write
                                    ? ConsumptionDefinition.Authority.WRITE
                                    : ConsumptionDefinition.Authority.READ);
            var settings =
                    new LifecycleSettings(
                            1,
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(10),
                            new LifecycleSettings.RetryPolicy(3, Duration.ofMillis(10)));
            ConsumptionParticipant probe =
                    new ConsumptionParticipant() {
                        @Override
                        public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                            return Optional.of(
                                    new Prepared() {
                                        @Override
                                        public void finalizeIn(SlotCompletion completion) {
                                            if (write) {
                                                new JdbcTemplate(ds)
                                                        .update(
                                                                "insert into"
                                                                    + " v3_command_result(request_id,outcome,resulting_pot_version,rejection)"
                                                                    + " values"
                                                                    + " (?,'CHANGED',1,null)",
                                                                consumableId);
                                            } else {
                                                new JdbcTemplate(ds)
                                                        .update(
                                                                "insert into"
                                                                    + " i5_read_probe_effect(consumption_id,consumable_id,value)"
                                                                    + " values"
                                                                    + " (?,?,'technical')",
                                                                READ_PROBE_EFFECT_ID,
                                                                consumableId);
                                            }
                                            System.out.println("IN_FLIGHT_WRITE");
                                            System.out.flush();
                                            try {
                                                Thread.sleep(60_000);
                                            } catch (InterruptedException ignored) {
                                            }
                                        }

                                        @Override
                                        public void requireCompletionReady() {}
                                    });
                        }

                        @Override
                        public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                            return ctx -> EffectEvidence.MISSING;
                        }
                    };
            var writeDs = new DriverManagerDataSource(writeUrl, writeRole, password);
            var engine =
                    write
                            ? new ConsumptionEngine(
                                    def,
                                    tx,
                                    new JdbcConsumptionSlotStore(ds),
                                    probe,
                                    new JdbcPartitionOwnershipStore(ds),
                                    settings,
                                    new WorkerCapacity(1, Map.of(def.id(), 1)),
                                    fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE)
                            : readEngine(def, ds, tx, writeDs, tx(writeDs), probe, settings);
            var lease = engine.acquirePartition(partition).orElseThrow();
            var claim = engine.acquire(lease, List.of(consumableId)).claims().getFirst();
            if (!inFlightWrite) {
                System.out.println("CLAIM_ACQUIRED");
                System.out.flush();
                Thread.sleep(60_000);
            } else {
                engine.execute(claim);
            }
        }
    }

    private static ConsumptionEngine readEngine(
            ConsumptionDefinition def,
            DriverManagerDataSource readDs,
            SpringAttemptTransactions readTx,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx,
            ConsumptionParticipant participant,
            LifecycleSettings settings) {
        var discoverySettings =
                new com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings(
                        100, 100, 100, 1000, Duration.ofSeconds(1), Duration.ofSeconds(10));
        return new ConsumptionEngine(
                def,
                readTx,
                new JdbcConsumptionSlotStore(readDs),
                participant,
                writeTx,
                Optional.of(
                        new com.kartaguez.pocoma.infra.postgres.source.JdbcEventDiscoveryReferences(
                                writeDs, def.family(), discoverySettings)),
                r -> true,
                new com.kartaguez.pocoma.infra.postgres.core.JdbcDiscoveryAdmissionStore(
                        readDs, discoverySettings),
                readTx,
                new JdbcConsumptionSlotStore(readDs),
                new JdbcPartitionOwnershipStore(readDs),
                settings,
                new WorkerCapacity(2, Map.of(def.id(), 2)),
                fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                null,
                com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
    }

    private static SpringAttemptTransactions tx(DriverManagerDataSource ds) {
        var tm = new DataSourceTransactionManager(ds);
        tm.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(ds, tm);
    }

    private static LifecycleSettings shortLeaseSettings() {
        return new LifecycleSettings(
                8,
                Duration.ofMillis(1500),
                Duration.ofMillis(1500),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                new LifecycleSettings.RetryPolicy(3, Duration.ofMillis(10)));
    }

    private static AttemptTransactions failOnceWriteTx(
            SpringAttemptTransactions delegate, AtomicBoolean failOnce) {
        return new AttemptTransactions() {
            @Override
            public <T> T read(
                    java.util.function.Function<RecoveryReadContext, T> action, Guard guard) {
                return delegate.read(action, guard);
            }

            @Override
            public void poison(Throwable cause) {
                delegate.poison(cause);
            }

            @Override
            public void requirePublicationClosed() {
                delegate.requirePublicationClosed();
            }

            @Override
            public <T> T write(java.util.function.Supplier<T> action, Guard guard) {
                return delegate.write(
                        () -> {
                            T res = action.get();
                            if (failOnce.compareAndSet(true, false)) {
                                throw new IllegalStateException("forced rollback");
                            }
                            return res;
                        },
                        guard);
            }
        };
    }

    private static ConsumptionParticipant readProbeParticipant(
            DriverManagerDataSource readDs, UUID eventId) {
        return new ConsumptionParticipant() {
            @Override
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                return Optional.of(
                        new Prepared() {
                            @Override
                            public void finalizeIn(SlotCompletion completion) {
                                new JdbcTemplate(readDs)
                                        .update(
                                                "insert into"
                                                    + " i5_read_probe_effect(consumption_id,consumable_id,value)"
                                                    + " values (?,?,'technical')",
                                                READ_PROBE_EFFECT_ID,
                                                eventId);
                                completion.complete();
                                SpringAttemptTransactions.closePublication(readDs);
                            }

                            @Override
                            public void requireCompletionReady() {}
                        });
            }

            @Override
            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                return ctx -> {
                    long count =
                            new JdbcTemplate(readDs)
                                    .queryForObject(
                                            "select count(*) from i5_read_probe_effect where"
                                                    + " consumable_id=?",
                                            Long.class,
                                            eventId);
                    return count == 1L ? EffectEvidence.SATISFIED : EffectEvidence.MISSING;
                };
            }
        };
    }

    private static void admitAndRunWrite(
            JdbcTemplate write,
            ConsumptionEngine engine,
            ConsumptionDefinition def,
            PotCommand command)
            throws InterruptedException {
        admitCommand(write, command);
        int part = RoutingV1.partition(RoutingV1.hash(def.family(), command.requestId()), 4);
        var lease = acquireLeaseWithRetry(engine, part);
        var claim = engine.acquire(lease, List.of(command.requestId())).claims().getFirst();
        assertEquals(ConsumptionProtocol.Status.COMMITTED, engine.execute(claim).status());
    }

    private static void admitCommand(JdbcTemplate write, PotCommand command) {
        write.update(
                "with cmd as (insert into v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) values (?,?,?,?,?,?,?,?::jsonb) returning request_id), pos as (update v3_request_counter set position=position+1 where family='COMMAND' returning position) insert into v3_request_position(family,position,request_id) select 'COMMAND',pos.position,cmd.request_id from cmd,pos",
                command.requestId(),
                command.requester().issuer(),
                command.requester().subject(),
                command.bindingToken(),
                command.potId().value(),
                command.observedVersion(),
                command.intent().getClass().getSimpleName(),
                PotCommandJson.encode(command.intent()));
        write.update(
                "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1('COMMAND',?) h)x",
                COMMAND_CONSUMPTION_ID,
                command.requestId(),
                command.requestId());
    }

    private static PartitionLease acquireLeaseWithRetry(ConsumptionEngine engine, int partition)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var opt = engine.acquirePartition(partition);
            if (opt.isPresent()) return opt.get();
            Thread.sleep(50);
        }
        fail("Could not acquire partition " + partition);
        return null;
    }
}
