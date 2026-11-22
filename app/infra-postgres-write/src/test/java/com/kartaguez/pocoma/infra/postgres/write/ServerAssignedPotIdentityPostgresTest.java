package com.kartaguez.pocoma.infra.postgres.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.AddShareholders;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Attachment;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreateExpense;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.CreatePot;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.Eui;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.ShareholderCreation;
import com.kartaguez.pocoma.domain.pot.ShareholderTransitions;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.engine.write.pot.ExecutePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotActorAuthority;
import com.kartaguez.pocoma.infra.postgres.source.JdbcCommandTerminalSource;
import com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotCommandObservation;
import com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotCommandStore;
import com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotHistoryAdapter;
import com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotRevisionAdapter;
import com.kartaguez.pocoma.infra.postgres.write.pot.PotCommandJson;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL qualification for server-assigned Pot, Shareholder, and Expense identities:
 * - WRITE V1 -> V2 upgrade with historical PENDING v1 commands and historical v1 results;
 * - Server-assigned CreatePot, AddShareholders (inputIndex order), CreateExpense;
 * - Rejected CreatePot (pot_id IS NULL, created_resources IS NULL);
 * - SQL check constraints;
 * - Interruptions before COMMIT, after COMMIT, and ambiguous COMMIT recovery observation.
 */
@Testcontainers
class ServerAssignedPotIdentityPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    private static final Eui CREATOR_EUI = new Eui("gov-issuer", "creator");
    private static final UserId CREATOR_USER = new UserId(new UUID(77, 1));
    private static final Weight ONE = new Weight(Fraction.ONE);
    private static final LocalDate DATE = LocalDate.of(2026, 11, 22);

    private record Env(
            String schema,
            String adminRole,
            String runtimeRole,
            DataSource source,
            JdbcTemplate jdbc,
            SpringAttemptTransactions tx,
            JdbcPotCommandStore commandStore,
            JdbcPotCommandObservation observation,
            JdbcCommandTerminalSource terminalSource,
            ExecutePotCommand engine) {}

    private Env createEnv(String targetVersion) {
        String schema = "gov_" + UUID.randomUUID().toString().replace("-", "");
        String adminRole = schema + "_a";
        String runtimeRole = schema + "_r";
        DataSource root =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var rootJdbc = new JdbcTemplate(root);
        rootJdbc.execute("create extension if not exists pgcrypto with schema public");
        rootJdbc.execute("create role " + adminRole + " login password 'fixture'");
        rootJdbc.execute("create role " + runtimeRole + " login password 'fixture'");
        rootJdbc.execute("create schema " + schema + " authorization " + adminRole);
        rootJdbc.execute("grant usage on schema " + schema + " to " + runtimeRole);
        DataSource adminDs =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public",
                        adminRole,
                        "fixture");
        Flyway.configure()
                .dataSource(adminDs)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .locations("classpath:db/write/migration")
                .target(targetVersion)
                .placeholders(
                        Map.of(
                                "expectedDatabase", POSTGRES.getDatabaseName(),
                                "expectedSchema", schema,
                                "expectedAdmin", adminRole,
                                "expectedRuntime", runtimeRole))
                .load()
                .migrate();
        DataSource source = adminDs;
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                """
                insert into consumption_routing_contract(
                    consumption_id, family, routing_version, partition_count
                ) values (
                    '98f7268d-d340-581c-b83d-010926223e14', 'COMMAND', 'sha256-v1', 4
                )
                """);
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        var tx = new SpringAttemptTransactions(source, manager, 15);
        var commandStore = new JdbcPotCommandStore(source, tx);
        var history = new JdbcPotHistoryAdapter(source);
        var revisions = new JdbcPotRevisionAdapter(source);
        var observation = new JdbcPotCommandObservation(source);
        PotActorAuthority authority =
                new PotActorAuthority() {
                    @Override
                    public Witness observe(PotCommand command) {
                        boolean valid =
                                "valid-token".equals(command.bindingToken())
                                        && CREATOR_EUI.equals(command.requester());
                        return new Witness(1L, valid ? Optional.of(CREATOR_USER) : Optional.empty());
                    }

                    @Override
                    public boolean protect(PotCommand command, Witness observed) {
                        return observe(command).equals(observed);
                    }
                };
        var engine =
                new ExecutePotCommand(
                        commandStore, observation, authority, history, revisions, Clock.systemUTC());
        var terminalSource = new JdbcCommandTerminalSource(source, tx);
        return new Env(
                schema,
                adminRole,
                runtimeRole,
                source,
                jdbc,
                tx,
                commandStore,
                observation,
                terminalSource,
                engine);
    }

    private void migrateToV2(Env env) {
        DataSource adminDs =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl() + "&currentSchema=" + env.schema + ",public",
                        env.adminRole,
                        "fixture");
        int executed =
                Flyway.configure()
                        .dataSource(adminDs)
                        .schemas(env.schema)
                        .defaultSchema(env.schema)
                        .createSchemas(false)
                        .locations("classpath:db/write/migration")
                        .target("2")
                        .placeholders(
                                Map.of(
                                        "expectedDatabase", POSTGRES.getDatabaseName(),
                                        "expectedSchema", env.schema,
                                        "expectedAdmin", env.adminRole,
                                        "expectedRuntime", env.runtimeRole))
                        .load()
                        .migrate()
                        .migrationsExecuted;
        assertEquals(1, executed, "Expected exactly 1 migration (V1 -> V2)");
    }

    private UUID admitAndClaim(Env env, PotCommand cmd) {
        env.jdbc.update(
                """
                with cmd as (
                    insert into v3_command(
                        request_id, requester_issuer, requester_subject, binding_token,
                        pot_id, observed_version, operation, payload
                    ) values (?,?,?,?,?,?,?,?::jsonb) returning request_id
                ), pos as (
                    update v3_request_counter set position = position + 1
                    where family = 'COMMAND' returning position
                )
                insert into v3_request_position(family, position, request_id)
                select 'COMMAND', pos.position, cmd.request_id from cmd, pos
                """,
                cmd.requestId(),
                cmd.requester().issuer(),
                cmd.requester().subject(),
                cmd.bindingToken(),
                cmd.potId() == null ? null : cmd.potId().value(),
                cmd.observedVersion(),
                cmd.intent().getClass().getSimpleName(),
                PotCommandJson.encode(cmd));
        env.jdbc.update(
                """
                insert into consumption_slot(consumption_id, consumable_id, partition_hash, partition_id)
                select '98f7268d-d340-581c-b83d-010926223e14', id, h, ((h::bigint % 4 + 4) % 4)::int
                from (select id, consumption_hash_v1('COMMAND', id) h from (select ?::uuid id) x) y
                """,
                cmd.requestId());
        var token = UUID.randomUUID();
        var tm = new DataSourceTransactionManager(env.source);
        tm.setNestedTransactionAllowed(false);
        Boolean claimed =
                new TransactionTemplate(tm)
                        .execute(
                                s ->
                                        env.jdbc.update(
                                                        """
                                                        update consumption_slot
                                                        set claim_token = ?, claimed_until = clock_timestamp() + interval '5 minutes'
                                                        where consumable_id = ? and state = 'AVAILABLE'
                                                        """,
                                                        token,
                                                        cmd.requestId())
                                                == 1);
        assertEquals(Boolean.TRUE, claimed);
        return token;
    }

    private Optional<ConsumptionParticipant.Prepared> prepareCommand(Env env, UUID requestId) {
        return env.engine.prepare(
                requestId,
                new PreparationReads() {
                    @Override
                    public <T> T source(Function<RecoveryReadContext, T> action) {
                        return env.tx.read(action);
                    }

                    @Override
                    public <T> T effects(Function<RecoveryReadContext, T> action) {
                        return env.tx.read(action);
                    }
                });
    }

    private void completeSlot(Env env, UUID requestId, UUID claimToken) {
        int deleted =
                env.jdbc.update(
                        "delete from consumption_slot where consumable_id = ? and claim_token = ?",
                        requestId,
                        claimToken);
        assertEquals(1, deleted);
    }

    private CommandTerminalResult loadTerminalResult(Env env, UUID requestId) {
        UUID completionEventId =
                env.jdbc.queryForObject(
                        "select event_id from v3_command_completion_event where subject_id = ?",
                        UUID.class,
                        requestId);
        return env.tx.read(ctx -> env.terminalSource.load(completionEventId, ctx));
    }

    private CommandTerminalResult executeCommand(Env env, UUID requestId, UUID claimToken) {
        var prepared = prepareCommand(env, requestId).orElseThrow();
        env.tx.write(
                () -> {
                    prepared.finalizeIn(
                            () -> {
                                completeSlot(env, requestId, claimToken);
                                prepared.requireCompletionReady();
                            });
                    return null;
                });
        return loadTerminalResult(env, requestId);
    }

    @Test
    void writeMigrationV1ToV2PreservesHistoricalPendingAndResultsAndExecutesV3Commands()
            throws Exception {
        var env = createEnv("1");

        // 1. Seed historical V1 completed CreatePot + PENDING v1 AddShareholders + PENDING v1 CreateExpense
        PotId legacyPot = new PotId(new UUID(90, 1));
        ShareholderId legacySh = new ShareholderId(new UUID(90, 2));
        ExpenseId legacyExp = new ExpenseId(new UUID(90, 3));

        UUID legacyCreatePotId = UUID.randomUUID();
        UUID legacyCreateClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                legacyCreatePotId,
                                CREATOR_EUI,
                                "valid-token",
                                legacyPot,
                                0,
                                new CreatePot(new Label("Legacy V1 Pot"))));

        // Execute historical CreatePot on V1 using raw V1 SQL (V2 columns don't exist yet on V1)
        env.tx.write(
                () -> {
                    env.jdbc.update(
                            "insert into v3_pot_identity(pot_id, current_version) values (?, 0)",
                            legacyPot.value());
                    env.jdbc.update(
                            "insert into v3_pot_version(pot_id, version, recorded_at) values (?, 1, now())",
                            legacyPot.value());
                    env.jdbc.update(
                            "insert into v3_pot_header(pot_id, object_id, local_version, active_from, deleted) values (?, ?, 1, 1, false)",
                            legacyPot.value(),
                            legacyPot.value());
                    env.jdbc.update(
                            "insert into v3_pot_details(pot_id, object_id, local_version, active_from, label, creator_id) values (?, ?, 1, 1, 'Legacy V1 Pot', ?)",
                            legacyPot.value(),
                            legacyPot.value(),
                            CREATOR_USER.value());
                    env.jdbc.update(
                            "update v3_command set status = 'TERMINAL' where request_id = ?",
                            legacyCreatePotId);
                    env.jdbc.update(
                            "insert into v3_command_result(request_id, outcome, resulting_pot_version) values (?, 'CHANGED', 1)",
                            legacyCreatePotId);
                    UUID potEventId = UUID.randomUUID();
                    env.jdbc.update(
                            "insert into v3_pot_event(event_id, pot_id, subject_version, request_id, recorded_at) values (?, ?, 1, ?, now())",
                            potEventId,
                            legacyPot.value(),
                            legacyCreatePotId);
                    env.jdbc.update(
                            "insert into v3_pot_event_component(event_id, kind, object_id, local_version) values (?, 'POT_HEADER', ?, 1)",
                            potEventId,
                            legacyPot.value());
                    Long potPos =
                            env.jdbc.queryForObject(
                                    "update v3_event_counter set position = position + 1 where family = 'POT' returning position",
                                    Long.class);
                    env.jdbc.update(
                            "insert into v3_event_position(family, position, event_id) values ('POT', ?, ?)",
                            potPos,
                            potEventId);
                    UUID compEventId = UUID.randomUUID();
                    env.jdbc.update(
                            "insert into v3_command_completion_event(event_id, subject_id, recorded_at) values (?, ?, now())",
                            compEventId,
                            legacyCreatePotId);
                    Long compPos =
                            env.jdbc.queryForObject(
                                    "update v3_event_counter set position = position + 1 where family = 'COMMAND_COMPLETION' returning position",
                                    Long.class);
                    env.jdbc.update(
                            "insert into v3_event_position(family, position, event_id) values ('COMMAND_COMPLETION', ?, ?)",
                            compPos,
                            compEventId);
                    completeSlot(env, legacyCreatePotId, legacyCreateClaim);
                    SpringAttemptTransactions.closePublication(env.source);
                    return null;
                });

        // Admit PENDING v1 AddShareholders and PENDING v1 CreateExpense before V1 -> V2 migration
        UUID legacyAddShId = UUID.randomUUID();
        UUID legacyAddShClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                legacyAddShId,
                                CREATOR_EUI,
                                "valid-token",
                                legacyPot,
                                1,
                                AddShareholders.legacy(
                                        List.of(
                                                new ShareholderTransitions.Creation(
                                                        legacySh,
                                                        new Name("Legacy Member"),
                                                        Optional.of(CREATOR_USER),
                                                        ONE)))));
        UUID legacyCreateExpId = UUID.randomUUID();
        UUID legacyCreateExpClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                legacyCreateExpId,
                                CREATOR_EUI,
                                "valid-token",
                                legacyPot,
                                2,
                                new CreateExpense(
                                        new ExpenseDetails(
                                                legacyPot, legacyExp, new Label("Legacy Exp"), DATE),
                                        new ExpenseFinancials(
                                                legacyPot,
                                                legacyExp,
                                                new Amount(new Fraction(10, 1)),
                                                legacySh,
                                                Map.of(legacySh, Optional.empty())))));

        // 2. Migrate WRITE V1 -> V2
        migrateToV2(env);

        // Historical V1 result is preserved and readable with potId resolved via coalesce(r.pot_id, c.pot_id)
        CommandTerminalResult histRes = loadTerminalResult(env, legacyCreatePotId);
        assertEquals(legacyPot.value(), histRes.potId());
        assertEquals("CHANGED", histRes.writeOutcome());
        assertNull(histRes.createdResources());

        // 3. Execute historical PENDING v1 commands after V2 upgrade
        var legacyAddRes = executeCommand(env, legacyAddShId, legacyAddShClaim);
        assertEquals("CHANGED", legacyAddRes.writeOutcome());
        assertEquals(2L, legacyAddRes.resultingPotVersion());
        assertEquals(
                1,
                env.jdbc.queryForObject(
                        "select count(*) from v3_shareholder_identity where object_id = ?",
                        Integer.class,
                        legacySh.value()));

        var legacyExpRes = executeCommand(env, legacyCreateExpId, legacyCreateExpClaim);
        assertEquals("CHANGED", legacyExpRes.writeOutcome());
        assertEquals(3L, legacyExpRes.resultingPotVersion());
        assertEquals(
                1,
                env.jdbc.queryForObject(
                        "select count(*) from v3_expense_identity where object_id = ?",
                        Integer.class,
                        legacyExp.value()));

        // 4. Execute server-assigned v3 CreatePot, AddShareholders (3 targets), CreateExpense
        UUID v3PotReq = UUID.randomUUID();
        UUID v3PotClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                v3PotReq,
                                CREATOR_EUI,
                                "valid-token",
                                null,
                                0,
                                new CreatePot(new Label("Server Assigned Pot"))));
        assertNull(
                env.jdbc.queryForObject(
                        "select pot_id from v3_command where request_id = ?",
                        UUID.class,
                        v3PotReq));
        var potTerm = executeCommand(env, v3PotReq, v3PotClaim);
        assertEquals("CHANGED", potTerm.writeOutcome());
        assertNotNull(potTerm.potId());
        assertNotNull(potTerm.createdResources());
        assertEquals(potTerm.potId(), potTerm.createdResources().potId());

        // AddShareholders with 3 targets -> verify exact inputIndex 0, 1, 2 ordering
        UUID v3ShReq = UUID.randomUUID();
        UUID v3ShClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                v3ShReq,
                                CREATOR_EUI,
                                "valid-token",
                                new PotId(potTerm.potId()),
                                1,
                                AddShareholders.of(
                                        List.of(
                                                ShareholderCreation.of(
                                                        new Name("Alice"), Attachment.SELF, ONE),
                                                ShareholderCreation.of(
                                                        new Name("Bob"), Attachment.FREE, ONE),
                                                ShareholderCreation.of(
                                                        new Name("Charlie"),
                                                        Attachment.FREE,
                                                        ONE)))));
        var shTerm = executeCommand(env, v3ShReq, v3ShClaim);
        assertEquals("CHANGED", shTerm.writeOutcome());
        var createdSh = shTerm.createdResources().shareholders();
        assertEquals(3, createdSh.size());
        assertEquals(List.of(0, 1, 2), createdSh.stream().map(s -> s.inputIndex()).toList());
        assertEquals(
                "Alice",
                env.jdbc.queryForObject(
                        "select name from v3_shareholder_details where object_id = ?",
                        String.class,
                        createdSh.get(0).shareholderId()));
        assertEquals(
                "Bob",
                env.jdbc.queryForObject(
                        "select name from v3_shareholder_details where object_id = ?",
                        String.class,
                        createdSh.get(1).shareholderId()));
        assertEquals(
                "Charlie",
                env.jdbc.queryForObject(
                        "select name from v3_shareholder_details where object_id = ?",
                        String.class,
                        createdSh.get(2).shareholderId()));

        // CreateExpense server-assigned
        ShareholderId aliceId = new ShareholderId(createdSh.get(0).shareholderId());
        UUID v3ExpReq = UUID.randomUUID();
        UUID v3ExpClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                v3ExpReq,
                                CREATOR_EUI,
                                "valid-token",
                                new PotId(potTerm.potId()),
                                2,
                                new CreateExpense(
                                        new Label("Server Expense"),
                                        DATE,
                                        new Amount(new Fraction(25, 2)),
                                        aliceId,
                                        List.of(
                                                new ExpenseFinancials.Participation(
                                                        aliceId, Optional.empty())))));
        var expTerm = executeCommand(env, v3ExpReq, v3ExpClaim);
        assertEquals("CHANGED", expTerm.writeOutcome());
        assertNotNull(expTerm.createdResources().expenseId());

        // 5. Rejected server-assigned CreatePot -> pot_id IS NULL, created_resources IS NULL, 0 pot rows created
        int potsBeforeReject =
                env.jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class);
        UUID rejReq = UUID.randomUUID();
        UUID rejClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                rejReq,
                                CREATOR_EUI,
                                "invalid-token",
                                null,
                                0,
                                new CreatePot(new Label("Rejected Pot"))));
        var rejTerm = executeCommand(env, rejReq, rejClaim);
        assertEquals("REJECTED", rejTerm.writeOutcome());
        assertNull(rejTerm.potId());
        assertNull(rejTerm.createdResources());
        assertEquals(
                potsBeforeReject,
                env.jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class));

        // 6. SQL check constraints reject invalid shapes (23514)
        try (var conn = env.source.getConnection()) {
            conn.setAutoCommit(false);
            try {
                SQLException ex1 =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        conn.createStatement()
                                                .execute(
                                                        "insert into v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) "
                                                                + "values (gen_random_uuid(),'i','s','t',null,1,'UpdatePotDetails','{\"label\":\"x\"}'::jsonb)"));
                assertEquals("23514", ex1.getSQLState());
            } finally {
                conn.rollback();
            }
            try {
                SQLException ex2 =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        conn.createStatement()
                                                .execute(
                                                        "insert into v3_command_result(request_id,outcome,rejection,created_resources) "
                                                                + "values (gen_random_uuid(),'REJECTED','INVALID_BINDING','{\"potId\":\"00000000-0000-0000-0000-000000000001\"}'::jsonb)"));
                assertTrue("23514".equals(ex2.getSQLState()) || "23503".equals(ex2.getSQLState()));
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    void interruptionsBeforeAfterCommitAndAmbiguousCommitOnAllThreeCreations() {
        var env = createEnv("2");

        // --- CreatePot: crash BEFORE commit -> rollback -> retry succeeds with single Pot ---
        UUID potReq = UUID.randomUUID();
        UUID potClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                potReq,
                                CREATOR_EUI,
                                "valid-token",
                                null,
                                0,
                                new CreatePot(new Label("Crash Safe Pot"))));
        var potPreparedBefore = prepareCommand(env, potReq).orElseThrow();
        assertThrows(
                RuntimeException.class,
                () ->
                        env.tx.write(
                                () -> {
                                    potPreparedBefore.finalizeIn(
                                            () -> completeSlot(env, potReq, potClaim));
                                    throw new RuntimeException("Simulated crash BEFORE COMMIT");
                                }));
        assertEquals(
                0, env.jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class));
        assertEquals(
                RecoveryObservation.NOT_COMPLETED,
                env.tx.read(ctx -> env.observation.observe(potReq, ctx)));

        // Retry succeeds and commits
        var potRes = executeCommand(env, potReq, potClaim);
        assertEquals("CHANGED", potRes.writeOutcome());
        PotId assignedPot = new PotId(potRes.potId());
        assertEquals(
                1, env.jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class));

        // Ambiguous COMMIT / crash AFTER commit: observation sees COMPLETED (even though v3_command.pot_id IS NULL!)
        assertEquals(
                RecoveryObservation.COMPLETED,
                env.tx.read(ctx -> env.observation.observe(potReq, ctx)));
        // Re-preparing already-terminal CreatePot returns empty (no duplicate Pot created!)
        assertTrue(prepareCommand(env, potReq).isEmpty());
        assertEquals(
                1, env.jdbc.queryForObject("select count(*) from v3_pot_identity", Integer.class));

        // --- AddShareholders: crash BEFORE commit -> retry -> ambiguous COMMIT observation ---
        UUID shReq = UUID.randomUUID();
        UUID shClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                shReq,
                                CREATOR_EUI,
                                "valid-token",
                                assignedPot,
                                1,
                                AddShareholders.of(
                                        List.of(
                                                ShareholderCreation.of(
                                                        new Name("First"), Attachment.SELF, ONE),
                                                ShareholderCreation.of(
                                                        new Name("Second"),
                                                        Attachment.FREE,
                                                        ONE)))));
        var shPreparedBefore = prepareCommand(env, shReq).orElseThrow();
        assertThrows(
                RuntimeException.class,
                () ->
                        env.tx.write(
                                () -> {
                                    shPreparedBefore.finalizeIn(
                                            () -> completeSlot(env, shReq, shClaim));
                                    throw new RuntimeException("Simulated crash BEFORE COMMIT");
                                }));
        assertEquals(
                0,
                env.jdbc.queryForObject(
                        "select count(*) from v3_shareholder_identity", Integer.class));
        assertEquals(
                RecoveryObservation.NOT_COMPLETED,
                env.tx.read(ctx -> env.observation.observe(shReq, ctx)));

        var shRes = executeCommand(env, shReq, shClaim);
        assertEquals(2, shRes.createdResources().shareholders().size());
        assertEquals(
                2,
                env.jdbc.queryForObject(
                        "select count(*) from v3_shareholder_identity", Integer.class));
        assertEquals(
                RecoveryObservation.COMPLETED,
                env.tx.read(ctx -> env.observation.observe(shReq, ctx)));
        assertTrue(prepareCommand(env, shReq).isEmpty());
        assertEquals(
                2,
                env.jdbc.queryForObject(
                        "select count(*) from v3_shareholder_identity", Integer.class));

        // --- CreateExpense: crash BEFORE commit -> retry -> ambiguous COMMIT observation ---
        ShareholderId payer =
                new ShareholderId(shRes.createdResources().shareholders().get(0).shareholderId());
        UUID expReq = UUID.randomUUID();
        UUID expClaim =
                admitAndClaim(
                        env,
                        new PotCommand(
                                expReq,
                                CREATOR_EUI,
                                "valid-token",
                                assignedPot,
                                2,
                                new CreateExpense(
                                        new Label("Crash Safe Expense"),
                                        DATE,
                                        new Amount(new Fraction(10, 1)),
                                        payer,
                                        List.of(
                                                new ExpenseFinancials.Participation(
                                                        payer, Optional.empty())))));
        var expPreparedBefore = prepareCommand(env, expReq).orElseThrow();
        assertThrows(
                RuntimeException.class,
                () ->
                        env.tx.write(
                                () -> {
                                    expPreparedBefore.finalizeIn(
                                            () -> completeSlot(env, expReq, expClaim));
                                    throw new RuntimeException("Simulated crash BEFORE COMMIT");
                                }));
        assertEquals(
                0,
                env.jdbc.queryForObject(
                        "select count(*) from v3_expense_identity", Integer.class));
        assertEquals(
                RecoveryObservation.NOT_COMPLETED,
                env.tx.read(ctx -> env.observation.observe(expReq, ctx)));

        var expRes = executeCommand(env, expReq, expClaim);
        assertNotNull(expRes.createdResources().expenseId());
        assertEquals(
                1,
                env.jdbc.queryForObject(
                        "select count(*) from v3_expense_identity", Integer.class));
        assertEquals(
                RecoveryObservation.COMPLETED,
                env.tx.read(ctx -> env.observation.observe(expReq, ctx)));
        assertTrue(prepareCommand(env, expReq).isEmpty());
        assertEquals(
                1,
                env.jdbc.queryForObject(
                        "select count(*) from v3_expense_identity", Integer.class));
    }
}
