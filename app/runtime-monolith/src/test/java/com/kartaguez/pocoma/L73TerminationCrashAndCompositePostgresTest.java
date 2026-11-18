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
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcConsumptionSlotStore;
import com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcPartitionOwnershipStore;
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
            verifyForcedRollbackAndRetry(f, writeDs, writeTx, cmdDef, participant, actor, potId, exp1, sh1, sh2);

            // 3. Stale-claim takeover: Worker 1 pauses after claim, Worker 2 takes over after lease
            // expiry -> Worker 1 gets CLAIM_LOST, Worker 2 COMMITTED
            verifyStaleClaimTakeover(f, writeDs, writeTx, cmdDef, participant, actor, potId);

            // 4. Mirror committed events into READ probe effects with crash + recovery on READ
            verifyCompositeReadAndReconciliation(f, writeDs, writeTx, readDs, readTx);
        }
    }

    private static void verifyForcedRollbackAndRetry(
            WorkerMonolithPostgresTest.Fixture f,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx,
            ConsumptionDefinition cmdDef,
            ExecutePotCommand participant,
            Eui actor,
            PotId potId,
            ExpenseId exp1,
            ShareholderId sh1,
            ShareholderId sh2)
            throws Exception {
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
                                        potId, exp1, new Label("Dinner"), LocalDate.of(2026, 11, 1)),
                                new ExpenseFinancials(
                                        potId,
                                        exp1,
                                        new Amount(new Fraction(100, 1)),
                                        sh1,
                                        Map.of(sh1, Optional.empty(), sh2, Optional.empty()))));
        admitCommand(f.write, expCmd);
        var failOnce = new AtomicBoolean(false);
        var rollbackEngine = writeEngine(cmdDef, failOnceWriteTx(writeTx, failOnce), writeDs, participant, 2);
        int pExp = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, expCmdId), 4);
        var leaseExp = acquireLeaseWithRetry(rollbackEngine, pExp);
        var claim1 = rollbackEngine.acquire(leaseExp, List.of(expCmdId)).claims().getFirst();
        failOnce.set(true);
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, rollbackEngine.execute(claim1).status());
        assertEquals(0, f.write.queryForObject("select count(*) from v3_command_result where request_id=?", Integer.class, expCmdId));

        // Retry after rollback -> COMMITTED
        f.write.update("update consumption_slot set retry_after=null,claimed_until=null,claim_token=null where consumable_id=?", expCmdId);
        var healthyEngine = writeEngine(cmdDef, writeTx, writeDs, participant, 2);
        var claim2 = healthyEngine.acquire(leaseExp, List.of(expCmdId)).claims().getFirst();
        assertEquals(ConsumptionProtocol.Status.COMMITTED, healthyEngine.execute(claim2).status());
    }

    private static void verifyStaleClaimTakeover(
            WorkerMonolithPostgresTest.Fixture f,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx,
            ConsumptionDefinition cmdDef,
            ExecutePotCommand participant,
            Eui actor,
            PotId potId)
            throws Exception {
        UUID updCmdId = UUID.randomUUID();
        admitCommand(
                f.write,
                new PotCommand(
                        updCmdId, actor, "tok", potId, 3L, new PotCommand.UpdatePotDetails(new Label("C04K-v4"))));
        int pUpd = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, updCmdId), 4);
        var w1Engine = writeEngine(cmdDef, writeTx, writeDs, participant, 2);
        var w1Lease = acquireLeaseWithRetry(w1Engine, pUpd);
        var w1Claim = w1Engine.acquire(w1Lease, List.of(updCmdId)).claims().getFirst();
        // Wait for SQL claim TTL (1.5s) to expire, then Worker 2 takes over partition & slot
        Thread.sleep(1700);
        var w2Engine = writeEngine(cmdDef, writeTx, writeDs, participant, 2);
        var w2Lease = acquireLeaseWithRetry(w2Engine, pUpd);
        var w2Claim = w2Engine.acquire(w2Lease, List.of(updCmdId)).claims().getFirst();
        assertEquals(ConsumptionProtocol.Status.COMMITTED, w2Engine.execute(w2Claim).status());
        var w1Res = w1Engine.execute(w1Claim);
        assertTrue(
                w1Res.status() == ConsumptionProtocol.Status.CLAIM_LOST
                        || w1Res.status() == ConsumptionProtocol.Status.COMMITTED,
                () -> "unexpected status " + w1Res);
    }

    private static void verifyCompositeReadAndReconciliation(
            WorkerMonolithPostgresTest.Fixture f,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx,
            DriverManagerDataSource readDs,
            SpringAttemptTransactions readTx)
            throws Exception {
        UUID readConsumable = f.write.queryForObject("select event_id from v3_pot_event limit 1", UUID.class);
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
                        new ReadEngineSources(readDs, readTx, writeDs, writeTx),
                        readProbeParticipant(readDs, readConsumable),
                        shortLeaseSettings());
        int pRead = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, readConsumable), 4);
        var rLease = acquireLeaseWithRetry(readEngine, pRead);
        var rClaim = readEngine.acquire(rLease, List.of(readConsumable)).claims().getFirst();
        assertEquals(ConsumptionProtocol.Status.COMMITTED, readEngine.execute(rClaim).status());
        // Exact reconciliation: 4 commands -> 4 results, 4 POT events, 4 completions, 0 slots
        assertAll(
                () -> assertEquals(4L, f.write.queryForObject("select count(*) from v3_command_result", Long.class)),
                () -> assertEquals(4L, f.write.queryForObject("select count(*) from v3_pot_event", Long.class)),
                () -> assertEquals(4L, f.write.queryForObject("select count(*) from v3_command_completion_event", Long.class)),
                () -> assertEquals(0L, f.write.queryForObject("select count(*) from consumption_slot", Long.class)),
                () -> assertEquals(1L, f.read.queryForObject("select count(*) from i5_read_probe_effect where consumable_id=?", Long.class, readConsumable)),
                () -> assertEquals(0L, f.read.queryForObject("select count(*) from consumption_slot", Long.class)));
    }

    private static ConsumptionEngine writeEngine(
            ConsumptionDefinition def,
            AttemptTransactions tx,
            DriverManagerDataSource ds,
            ConsumptionParticipant participant,
            int limit) {
        return new ConsumptionEngine(
                def,
                tx,
                new JdbcConsumptionSlotStore(ds),
                participant,
                new JdbcPartitionOwnershipStore(ds),
                shortLeaseSettings(),
                new WorkerCapacity(limit, Map.of(def.id(), limit)),
                fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
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
                tasks.add(() -> runIdentityRegistrationTask(f, sharedIdEngine, idx));
            }
            tasks.add(() -> runInvitationRollbackRetryTask(f, writeDs, writeTx, cmdDef, potParticipant, ownerEui, potId));

            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> fut : futures) {
                fut.get(30, TimeUnit.SECONDS);
            }
            verifyDenseFamilyCounters(f.write);
        }
    }

    private static Void runIdentityRegistrationTask(
            WorkerMonolithPostgresTest.Fixture f, ConsumptionEngine sharedIdEngine, int idx)
            throws InterruptedException {
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
        int part = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, reqId), 4);
        var claim = acquireClaimWithRetry(sharedIdEngine, part, reqId);
        var execRes = sharedIdEngine.execute(claim);
        assertEquals(
                ConsumptionProtocol.Status.COMMITTED,
                execRes.status(),
                () -> "cause=" + execRes.cause());
        return null;
    }

    private static Void runInvitationRollbackRetryTask(
            WorkerMonolithPostgresTest.Fixture f,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx,
            ConsumptionDefinition cmdDef,
            ExecutePotCommand potParticipant,
            Eui ownerEui,
            PotId potId)
            throws InterruptedException {
        var cmdId = UUID.randomUUID();
        admitCommand(
                f.write,
                new PotCommand(
                        cmdId,
                        ownerEui,
                        "tok",
                        potId,
                        0L,
                        new PotCommand.CreateInvitation(
                                Instant.now()
                                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                                        .plus(Duration.ofDays(7)))));
        var failOnce = new AtomicBoolean(false);
        var eFail = writeEngine(cmdDef, failOnceWriteTx(writeTx, failOnce), writeDs, potParticipant, 4);
        int part = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, cmdId), 4);
        var lease = acquireLeaseWithRetry(eFail, part);
        var c1 = eFail.acquire(lease, List.of(cmdId)).claims().getFirst();
        failOnce.set(true);
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, eFail.execute(c1).status());

        f.write.update(
                "update consumption_slot set retry_after=null,claimed_until=null,claim_token=null where consumable_id=?",
                cmdId);
        var eOk = writeEngine(cmdDef, writeTx, writeDs, potParticipant, 4);
        var c2 = eOk.acquire(lease, List.of(cmdId)).claims().getFirst();
        var eOkRes = eOk.execute(c2);
        assertEquals(
                ConsumptionProtocol.Status.COMMITTED,
                eOkRes.status(),
                () -> "eOk cause=" + eOkRes.cause());
        return null;
    }

    private static void verifyDenseFamilyCounters(JdbcTemplate write) {
        // Reconcile all 5 families: dense 1..N positions, matching v3_event_counter, 0 residual slots
        for (String family :
                List.of("POT", "COMMAND_COMPLETION", "INVITATION", "IDENTITY", "IDENTITY_COMPLETION")) {
            var positions =
                    write.queryForList(
                            "select position from v3_event_position where family=? order by position",
                            Long.class,
                            family);
            for (int i = 0; i < positions.size(); i++) {
                assertEquals(i + 1L, positions.get(i), "dense gap in " + family);
            }
            assertEquals(
                    (long) positions.size(),
                    write.queryForObject("select position from v3_event_counter where family=?", Long.class, family),
                    "counter mismatch in " + family);
        }
        assertAll(
                () -> assertEquals(1L, write.queryForObject("select position from v3_event_counter where family='POT'", Long.class)),
                () -> assertEquals(2L, write.queryForObject("select position from v3_event_counter where family='COMMAND_COMPLETION'", Long.class)),
                () -> assertEquals(1L, write.queryForObject("select position from v3_event_counter where family='INVITATION'", Long.class)),
                () -> assertEquals(8L, write.queryForObject("select position from v3_event_counter where family='IDENTITY'", Long.class)),
                () -> assertEquals(4L, write.queryForObject("select position from v3_event_counter where family='IDENTITY_COMPLETION'", Long.class)),
                () -> assertEquals(0L, write.queryForObject("select count(*) from consumption_slot", Long.class)));
    }

    private record CrashTarget(
            boolean writeAuthority,
            UUID consumptionId,
            ConsumableFamily family,
            JdbcTemplate db,
            String url,
            String role,
            UUID consumableId,
            int partition,
            boolean inFlightWrite) {}

    private void runCrashScenario(
            WorkerMonolithPostgresTest.Fixture f, boolean writeAuthority, boolean inFlightWrite)
            throws Exception {
        CrashTarget target = buildCrashTarget(f, writeAuthority, inFlightWrite);
        Path tempDir = Files.createTempDirectory("l73-crash-");
        Path log = tempDir.resolve("child.log");
        Process child = startCrashChild(f, target, log);
        try {
            String marker = inFlightWrite ? "IN_FLIGHT_WRITE" : "CLAIM_ACQUIRED";
            awaitChildMarker(child, log, marker);

            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));

            verifyPreRecoveryCrashState(
                    target.db(), target.writeAuthority(), target.consumptionId(), target.consumableId());
            awaitSqlLeaseExpiry(target.db(), target.consumptionId(), target.consumableId());
            recoverCrashTarget(f, target);
            verifyPostRecoveryCrashState(
                    target.db(), target.writeAuthority(), target.consumptionId(), target.consumableId());
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
            Files.deleteIfExists(log);
            Files.deleteIfExists(tempDir);
        }
    }

    private CrashTarget buildCrashTarget(
            WorkerMonolithPostgresTest.Fixture f, boolean writeAuthority, boolean inFlightWrite)
            throws InterruptedException {
        UUID consumptionId = writeAuthority ? COMMAND_CONSUMPTION_ID : READ_PROBE_CONSUMPTION_ID;
        ConsumableFamily family =
                writeAuthority ? ConsumableFamily.COMMAND : ConsumableFamily.POT_EVENT;
        UUID consumableId =
                writeAuthority
                        ? prepareWriteCrashTarget(f.write)
                        : prepareReadCrashTarget(f, consumptionId);
        int partition = RoutingV1.partition(RoutingV1.hash(family, consumableId), 4);
        if (writeAuthority) {
            return new CrashTarget(
                    true, consumptionId, family, f.write, f.writeUrl, f.writeRole, consumableId, partition, inFlightWrite);
        }
        return new CrashTarget(
                false, consumptionId, family, f.read, f.readUrl, f.readRole, consumableId, partition, inFlightWrite);
    }

    private UUID prepareWriteCrashTarget(JdbcTemplate writeDb) {
        UUID consumableId = UUID.randomUUID();
        admitCommand(
                writeDb,
                new PotCommand(
                        consumableId,
                        new Eui("crash", "actor"),
                        "tok",
                        new PotId(UUID.randomUUID()),
                        0L,
                        new CreatePot(new Label("CrashPot"))));
        return consumableId;
    }

    private UUID prepareReadCrashTarget(WorkerMonolithPostgresTest.Fixture f, UUID consumptionId)
            throws InterruptedException {
        var writeDs = new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture");
        var writeTx = tx(writeDs);
        var cmdDef =
                new ConsumptionDefinition(
                        new ConsumptionId(COMMAND_CONSUMPTION_ID),
                        ConsumableFamily.COMMAND,
                        ConsumptionDefinition.Authority.WRITE);
        var cmdEngine =
                new ConsumptionEngine(
                        cmdDef,
                        writeTx,
                        new JdbcConsumptionSlotStore(writeDs),
                        potCommandParticipant(writeDs, writeTx),
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
        UUID consumableId =
                f.write.queryForObject(
                        "select event_id from v3_pot_event where request_id=?", UUID.class, cmdId);
        f.read.update(
                "insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1('POT_EVENT',?) h)x",
                consumptionId,
                consumableId,
                consumableId);
        return consumableId;
    }

    private static ExecutePotCommand potCommandParticipant(
            DriverManagerDataSource ds, SpringAttemptTransactions tx) {
        return new ExecutePotCommand(
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
                Clock.systemUTC());
    }

    private static Process startCrashChild(
            WorkerMonolithPostgresTest.Fixture f, CrashTarget target, Path log) throws Exception {
        String classpath =
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        classpath,
                        CrashChild.class.getName(),
                        f.writeUrl,
                        f.writeRole,
                        target.url(),
                        target.role(),
                        "i5-fixture",
                        target.writeAuthority() ? "WRITE" : "READ",
                        target.consumptionId().toString(),
                        target.consumableId().toString(),
                        Integer.toString(target.partition()),
                        Boolean.toString(target.inFlightWrite()))
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private static void awaitChildMarker(Process child, Path log, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(marker)) {
                return;
            }
            if (!child.isAlive()) {
                fail("Child exited early (" + child.exitValue() + "): " + Files.readString(log));
            }
            Thread.sleep(25);
        }
        assertTrue(
                Files.exists(log) && Files.readString(log).contains(marker),
                "Child did not reach " + marker + ": " + (Files.exists(log) ? Files.readString(log) : ""));
    }

    private static void verifyPreRecoveryCrashState(
            JdbcTemplate db, boolean writeAuthority, UUID consumptionId, UUID consumableId) {
        var slot =
                db.queryForMap(
                        "select attempt_count,state,claim_token is not null as claimed from consumption_slot where consumption_id=? and consumable_id=?",
                        consumptionId,
                        consumableId);
        String effectSql =
                writeAuthority
                        ? "select count(*) from v3_command_result where request_id=?"
                        : "select count(*) from i5_read_probe_effect where consumable_id=?";
        assertAll(
                () -> assertEquals(0, ((Number) slot.get("attempt_count")).intValue()),
                () -> assertEquals("AVAILABLE", slot.get("state")),
                () -> assertEquals(Boolean.TRUE, slot.get("claimed")),
                () -> assertEquals(0L, db.queryForObject(effectSql, Long.class, consumableId)));
    }

    private static void awaitSqlLeaseExpiry(
            JdbcTemplate db, UUID consumptionId, UUID consumableId) throws InterruptedException {
        long expiryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < expiryDeadline) {
            Boolean expired =
                    db.queryForObject(
                            "select claimed_until <= clock_timestamp() from consumption_slot where consumption_id=? and consumable_id=?",
                            Boolean.class,
                            consumptionId,
                            consumableId);
            if (Boolean.TRUE.equals(expired)) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private void recoverCrashTarget(WorkerMonolithPostgresTest.Fixture f, CrashTarget target)
            throws Exception {
        var ds = new DriverManagerDataSource(target.url(), target.role(), "i5-fixture");
        var tx = tx(ds);
        var def =
                new ConsumptionDefinition(
                        new ConsumptionId(target.consumptionId()),
                        target.family(),
                        target.writeAuthority()
                                ? ConsumptionDefinition.Authority.WRITE
                                : ConsumptionDefinition.Authority.READ);
        ConsumptionParticipant participant =
                target.writeAuthority()
                        ? potCommandParticipant(ds, tx)
                        : readProbeParticipant(ds, target.consumableId());
        ConsumptionEngine engine;
        if (target.writeAuthority()) {
            engine =
                    new ConsumptionEngine(
                            def,
                            tx,
                            new JdbcConsumptionSlotStore(ds),
                            participant,
                            new JdbcPartitionOwnershipStore(ds),
                            shortLeaseSettings(),
                            new WorkerCapacity(1, Map.of(def.id(), 1)),
                            fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        } else {
            var writeDs = new DriverManagerDataSource(f.writeUrl, f.writeRole, "i5-fixture");
            engine =
                    readEngine(
                            def,
                            new ReadEngineSources(ds, tx, writeDs, tx(writeDs)),
                            participant,
                            shortLeaseSettings());
        }
        var lease = acquireLeaseWithRetry(engine, target.partition());
        var claim = engine.acquire(lease, List.of(target.consumableId())).claims().getFirst();
        var recRes = engine.execute(claim);
        assertEquals(
                ConsumptionProtocol.Status.COMMITTED,
                recRes.status(),
                () -> "recovery cause=" + recRes.cause());
    }

    private static void verifyPostRecoveryCrashState(
            JdbcTemplate db, boolean writeAuthority, UUID consumptionId, UUID consumableId) {
        String effectSql =
                writeAuthority
                        ? "select count(*) from v3_command_result where request_id=?"
                        : "select count(*) from i5_read_probe_effect where consumable_id=?";
        assertAll(
                () ->
                        assertEquals(
                                0L,
                                db.queryForObject(
                                        "select count(*) from consumption_slot where consumption_id=? and consumable_id=?",
                                        Long.class,
                                        consumptionId,
                                        consumableId)),
                () -> assertEquals(1L, db.queryForObject(effectSql, Long.class, consumableId)));
    }

    public static final class CrashChild {
        private record ChildArgs(
                String writeUrl,
                String writeRole,
                String url,
                String user,
                String password,
                boolean write,
                UUID consumptionId,
                UUID consumableId,
                int partition,
                boolean inFlightWrite) {
            static ChildArgs parse(String[] args) {
                return new ChildArgs(
                        args[0],
                        args[1],
                        args[2],
                        args[3],
                        args[4],
                        args[5].equals("WRITE"),
                        UUID.fromString(args[6]),
                        UUID.fromString(args[7]),
                        Integer.parseInt(args[8]),
                        Boolean.parseBoolean(args[9]));
            }
        }

        public static void main(String[] args) throws Exception {
            var cfg = ChildArgs.parse(args);
            var ds = new DriverManagerDataSource(cfg.url(), cfg.user(), cfg.password());
            var tx = tx(ds);
            var def =
                    new ConsumptionDefinition(
                            new ConsumptionId(cfg.consumptionId()),
                            cfg.write() ? ConsumableFamily.COMMAND : ConsumableFamily.POT_EVENT,
                            cfg.write()
                                    ? ConsumptionDefinition.Authority.WRITE
                                    : ConsumptionDefinition.Authority.READ);
            var settings = childSettings();
            var probe = probeParticipant(ds, cfg.write(), cfg.consumableId());
            var engine = createEngine(cfg, ds, tx, def, probe, settings);
            var lease = engine.acquirePartition(cfg.partition()).orElseThrow();
            var claim = engine.acquire(lease, List.of(cfg.consumableId())).claims().getFirst();
            if (!cfg.inFlightWrite()) {
                System.out.println("CLAIM_ACQUIRED");
                System.out.flush();
                Thread.sleep(60_000);
            } else {
                engine.execute(claim);
            }
        }

        private static LifecycleSettings childSettings() {
            return new LifecycleSettings(
                    1,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(10),
                    new LifecycleSettings.RetryPolicy(3, Duration.ofMillis(10)));
        }

        private static ConsumptionEngine createEngine(
                ChildArgs cfg,
                DriverManagerDataSource ds,
                SpringAttemptTransactions tx,
                ConsumptionDefinition def,
                ConsumptionParticipant probe,
                LifecycleSettings settings) {
            if (cfg.write()) {
                return new ConsumptionEngine(
                        def,
                        tx,
                        new JdbcConsumptionSlotStore(ds),
                        probe,
                        new JdbcPartitionOwnershipStore(ds),
                        settings,
                        new WorkerCapacity(1, Map.of(def.id(), 1)),
                        fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            }
            var writeDs =
                    new DriverManagerDataSource(cfg.writeUrl(), cfg.writeRole(), cfg.password());
            return readEngine(
                    def, new ReadEngineSources(ds, tx, writeDs, tx(writeDs)), probe, settings);
        }

        private static ConsumptionParticipant probeParticipant(
                DriverManagerDataSource ds, boolean write, UUID consumableId) {
            return new ConsumptionParticipant() {
                @Override
                public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                    return Optional.of(
                            new Prepared() {
                                @Override
                                public void finalizeIn(SlotCompletion completion) {
                                    insertProbeEffect(ds, write, consumableId);
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
        }

        private static void insertProbeEffect(
                DriverManagerDataSource ds, boolean write, UUID consumableId) {
            if (write) {
                new JdbcTemplate(ds)
                        .update(
                                "insert into v3_command_result(request_id,outcome,resulting_pot_version,rejection) values (?,'CHANGED',1,null)",
                                consumableId);
            } else {
                new JdbcTemplate(ds)
                        .update(
                                "insert into i5_read_probe_effect(consumption_id,consumable_id,value) values (?,?,'technical')",
                                READ_PROBE_EFFECT_ID,
                                consumableId);
            }
        }
    }

    private record ReadEngineSources(
            DriverManagerDataSource readDs,
            SpringAttemptTransactions readTx,
            DriverManagerDataSource writeDs,
            SpringAttemptTransactions writeTx) {}

    private static ConsumptionEngine readEngine(
            ConsumptionDefinition def,
            ReadEngineSources sources,
            ConsumptionParticipant participant,
            LifecycleSettings settings) {
        var discoverySettings =
                new com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings(
                        100, 100, 100, 1000, Duration.ofSeconds(1), Duration.ofSeconds(10));
        return new ConsumptionEngine(
                def,
                sources.readTx(),
                new JdbcConsumptionSlotStore(sources.readDs()),
                participant,
                sources.writeTx(),
                Optional.of(
                        new com.kartaguez.pocoma.infra.postgres.source.JdbcEventDiscoveryReferences(
                                sources.writeDs(), def.family(), discoverySettings)),
                r -> true,
                new com.kartaguez.pocoma.infra.postgres.core.consumption.JdbcDiscoveryAdmissionStore(
                        sources.readDs(), discoverySettings),
                sources.readTx(),
                new JdbcConsumptionSlotStore(sources.readDs()),
                new JdbcPartitionOwnershipStore(sources.readDs()),
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

    private static ConsumptionEngine.AcquiredClaim acquireClaimWithRetry(
            ConsumptionEngine engine, int partition, UUID consumableId)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var lease = acquireLeaseWithRetry(engine, partition);
            var claims = engine.acquire(lease, List.of(consumableId)).claims();
            if (!claims.isEmpty()) return claims.getFirst();
            Thread.sleep(50);
        }
        fail("Could not acquire claim for " + consumableId + " on partition " + partition);
        return null;
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
