package com.kartaguez.pocoma.runtime.task;

import static com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name.*;
import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.domain.pot.invitation.Invitation;
import com.kartaguez.pocoma.domain.projection.PotVisibility;
import com.kartaguez.pocoma.infra.postgres.read.JdbcInvitationStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVisibilityStore;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Testcontainers
class ReadAvailabilityPostgresTest {
    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse(SubobjectPostgresFixture.IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() {
        SubobjectPostgresFixture.initialize(PG);
    }

    @Test
    void latePotVersionsPublishWithoutRegressingTheDeletedCurrentPot() throws Exception {
        try (var fixture = new ReadParticipantsFixture(PG, "c3-terminal-out-of-order")) {
            enable(fixture);
            var read = fixture.f.read.oracle;
            var terminal = fixture.mutatePot(new PotCommand.DeletePot(), 1);
            fixture.enroll();

            int before = fixture.f.read.source.statements.size();
            fixture.execute(POT_VERSION, terminal);
            var suffix = fixture.f.read.source.statements.stream()
                    .skip(before)
                    .filter(sql -> sql.contains("update read_availability_counter"))
                    .toList();
            assertEquals(2, suffix.size());
            assertTrue(suffix.getFirst().contains("insert into read_availability_event"));
            assertTrue(suffix.getLast().contains("insert into read_current_availability"));
            fixture.execute(POT_BALANCE, terminal);
            fixture.execute(POT_VISIBILITY, terminal);
            fixture.execute(POT_VERSION, fixture.event(POT_VERSION));
            fixture.execute(POT_BALANCE, fixture.event(POT_BALANCE));
            fixture.execute(POT_VISIBILITY, fixture.event(POT_VISIBILITY));
            for (var family : List.of(COMMAND_RESULT, IDENTITY_REQUEST_RESULT, CURRENT_BINDING)) {
                fixture.execute(family, fixture.event(family));
            }

            assertEquals(List.of(2L, 1L), read.queryForList("""
                    select logical_version from read_availability_event
                    where family = 'POT_VERSION_AVAILABLE' order by position
                    """, Long.class));
            assertEquals(List.of(1L), read.queryForList("""
                    select logical_version from read_availability_event
                    where family = 'POT_BALANCE_AVAILABLE' order by position
                    """, Long.class));
            assertEquals(List.of(2L, 2L), read.queryForList("""
                    select logical_version from read_current_availability
                    where family in ('CURRENT_POT_CHANGED', 'POT_VISIBILITY_CHANGED') order by family
                    """, Long.class));
            assertEquals(0, read.queryForObject("select count(*) from read_user_pot where visible", Integer.class));
            assertEquals(1L, head(read, "CURRENT_POT_CHANGED"));
            assertEquals(1L, head(read, "POT_VISIBILITY_CHANGED"));
            assertEquals(1L, head(read, "COMMAND_RESULT_AVAILABLE"));
            assertEquals(1L, head(read, "IDENTITY_REQUEST_RESULT_AVAILABLE"));
            assertEquals(1L, head(read, "CURRENT_BINDING_CHANGED"));
            assertTrue(read.queryForObject("select read_availability_valid()", Boolean.class));
        }
    }

    @Test
    void failureOfSecondPublicationRollsBackFirstPublicationAndRestoresTheSlot() throws Exception {
        try (var fixture = new ReadParticipantsFixture(PG, "c3-dual-rollback")) {
            enable(fixture);
            fixture.enroll();
            var db = fixture.f.read;
            db.oracle.execute("""
                    create function reject_current_pot() returns trigger language plpgsql as $$
                    begin raise exception 'injected second publication failure' using errcode = '23514'; end
                    $$;
                    create trigger reject_current_pot before insert on read_current_availability
                    for each row when (new.family = 'CURRENT_POT_CHANGED') execute function reject_current_pot()
                    """);
            var engine = fixture.engines.get(POT_VERSION);
            var event = fixture.event(POT_VERSION);
            var attempt = engine.execute(fixture.acquire(POT_VERSION, engine, event));
            assertNotEquals(ConsumptionProtocol.Status.COMMITTED, attempt.status());
            assertEquals("23514", state(attempt.cause()));
            assertEquals(1, fixture.slots(POT_VERSION, event));
            for (String table : List.of("read_pot_version", "read_current_pot", "read_availability_event",
                    "read_current_availability")) {
                assertEquals(0, db.oracle.queryForObject("select count(*) from " + table, Integer.class), table);
            }
            assertEquals(0L, head(db.oracle, "POT_VERSION_AVAILABLE"));
            assertEquals(0L, head(db.oracle, "CURRENT_POT_CHANGED"));
        }
    }

    @Test
    void lostCommitAcknowledgementForTwoPublicationsRemainsRecoverableAfterSupersession() throws Exception {
        try (var fixture = new ReadParticipantsFixture(PG, "c3-dual-lost-ack");
                var proxy = new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
            enable(fixture);
            var terminal = fixture.mutatePot(new PotCommand.DeletePot(), 1);
            fixture.enroll();
            var db = fixture.f.read;
            var endpoint = new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:" + proxy.localPort() + "/" + PG.getDatabaseName()
                            + "?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5"
                            + "&currentSchema=" + db.schema, db.runtimeRole, "i1-fixture");
            var observed = new SubobjectPostgresFixture.ObservedDataSource(endpoint);
            observed.after = sql -> {
                if (sql.contains("insert into read_current_availability")) {
                    proxy.arm(CommitAcknowledgementProxy.Fault.AFTER_COMMIT);
                }
            };
            var owner = SubobjectPostgresFixture.owner(observed);
            var participant = ReadParticipants.select("MaterializePotVersion", fixture.f.primary,
                    fixture.f.sourceOwner, observed, owner).participant();
            var engine = fixture.engine(POT_VERSION, participant, observed, owner);
            var initial = fixture.event(POT_VERSION);
            var claim = fixture.acquire(POT_VERSION, engine, initial);
            var attempt = engine.execute(claim);

            assertEquals(1, proxy.committedReplyCount());
            assertEquals(Resolution.UNKNOWN, attempt.resolution());
            assertEquals(RecoveryObservation.COMPLETED, attempt.observation());
            assertEquals(0, fixture.slots(POT_VERSION, initial));
            var published = db.oracle.queryForMap("""
                    select * from read_availability_event where family = 'POT_VERSION_AVAILABLE'
                    """);

            fixture.execute(POT_VERSION, terminal);
            assertEquals(RecoveryObservation.COMPLETED, fixture.engines.get(POT_VERSION).observe(claim.claim()));
            assertEquals(published, db.oracle.queryForMap("""
                    select * from read_availability_event where family = 'POT_VERSION_AVAILABLE' and position = 1
                    """));
            assertEquals(2L, head(db.oracle, "CURRENT_POT_CHANGED"));
        }
    }

    @Test
    void currentPagesCatchReplacedPositionsAndLateKeysWhileSameFamilyWritersSerialize() throws Exception {
        try (var db = SubobjectPostgresFixture.currentBaseline(PG, "c3-paginated-discovery")) {
            install(db);
            var store = new JdbcPotVisibilityStore(db.source, db.tx, true);
            var a = new UUID(0, 20);
            var b = new UUID(0, 30);
            var late = new UUID(0, 10);
            publishVisibility(db, store, a, 1);
            publishVisibility(db, store, b, 1);
            Page first = page(db.oracle, 0, 1);
            assertEquals(new Page(2, 1, List.of(a)), first);

            var waiting = new CompletableFuture<Integer>();
            var allocated = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var replacing = db.threads.submit(() -> db.tx.write(() -> {
                writeVisibility(store, b, 2);
                allocated.countDown();
                await(release);
                return null;
            }));
            try {
                await(allocated);
                var inserting = db.threads.submit(() -> db.tx.write(() -> {
                    waiting.complete(db.sql.queryForObject("select pg_backend_pid()", Integer.class));
                    writeVisibility(store, late, 1);
                    return null;
                }));
                db.waitForLock(waiting.get(5, TimeUnit.SECONDS));
                assertEquals(new Page(2, 2, List.of(b)), page(db.oracle, first.next(), 1));
                release.countDown();
                replacing.get(5, TimeUnit.SECONDS);
                inserting.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }

            // The reader only acknowledged the first page. The old position 2 disappeared.
            assertEquals(new Page(4, 3, List.of(b)), page(db.oracle, first.next(), 1));
            assertEquals(new Page(4, 4, List.of(late)), page(db.oracle, 3, 2));
            assertEquals(new Page(4, 4, List.of()), page(db.oracle, 4, 2));
            assertEquals("22023", state(assertThrows(DataAccessException.class, () -> page(db.oracle, 5, 1))));
            publishVisibility(db, store, b, 2);
            publishVisibility(db, store, b, 1);
            assertEquals(4L, head(db.oracle, "POT_VISIBILITY_CHANGED"));
            assertEquals(3, db.oracle.queryForObject("select count(*) from read_current_availability", Integer.class));
            assertTrue(db.oracle.queryForObject("select read_availability_valid()", Boolean.class));
        }
    }

    @Test
    void invitationAdvancesOnlyWhenTheStoredVersionAdvances() throws Exception {
        try (var db = SubobjectPostgresFixture.currentBaseline(PG, "c3-invitation")) {
            install(db);
            var store = new JdbcInvitationStore(db.source, db.tx);
            var key = UUID.randomUUID();
            var request = UUID.randomUUID();
            var pot = UUID.randomUUID();
            Invitation one = new Invitation(key, 1, request, "issuer", "subject", pot,
                    Instant.parse("2030-01-01T00:00:00Z"), "0".repeat(64), "v1.first");
            Invitation two = new Invitation(key, 2, request, "issuer", "subject", pot,
                    Instant.parse("2030-02-01T00:00:00Z"), "0".repeat(64), "v1.second");
            for (Invitation invitation : List.of(one, two, one, two)) {
                db.tx.write(() -> {
                    boolean advanced = store.put(invitation);
                    store.requireCompletionReady(invitation, advanced);
                    store.closePublication(invitation, advanced);
                    return null;
                });
            }
            assertEquals(2L, head(db.oracle, "CURRENT_INVITATION_CHANGED"));
            assertEquals(2L, db.oracle.queryForObject("""
                    select logical_version from read_current_availability
                    where family = 'CURRENT_INVITATION_CHANGED' and projection_key = ?
                    """, Long.class, key));
            assertTrue(db.oracle.queryForObject("select read_availability_valid()", Boolean.class));
        }
    }

    @Test
    void migrationRefusesPopulatedV4AndSqlRejectsPartialPublication() throws Exception {
        try (var fixture = new ReadParticipantsFixture(PG, "c3-populated-v4")) {
            fixture.enroll();
            fixture.execute(COMMAND_RESULT, fixture.event(COMMAND_RESULT));
            var db = fixture.f.read;
            assertEquals("23514", state(assertThrows(FlywayException.class, () -> install(db))));
            assertNull(db.oracle.queryForObject("select to_regclass('read_availability_counter')::text", String.class));
        }
        try (var db = SubobjectPostgresFixture.currentBaseline(PG, "c3-sql-constraints")) {
            install(db);
            assertEquals("42501", state(assertThrows(DataAccessException.class, () ->
                    db.sql.update("update read_availability_counter set writer_xid = pg_current_xact_id()"))));
            assertEquals("23514", state(assertThrows(DataAccessException.class, () ->
                    db.sql.update("update read_availability_counter set position = 1 where family = 'POT_VERSION_AVAILABLE'"))));
            assertEquals("23514", state(assertThrows(DataAccessException.class, () ->
                    db.sql.update("insert into read_pot_visibility_state values (?, 1)", UUID.randomUUID()))));
            assertEquals("23514", state(assertThrows(DataAccessException.class, () ->
                    db.sql.update("insert into read_pot_visibility_state values (?, 0)", UUID.randomUUID()))));
            assertTrue(db.oracle.queryForObject("select read_availability_valid()", Boolean.class));
        }
    }

    @Test
    void genericEffectLookupExecutesOnlyTheRequestedFamily() throws Exception {
        try (var db = SubobjectPostgresFixture.currentBaseline(PG, "c3-generic-effect-plan")) {
            install(db);
            try (var connection = db.source.getConnection(); var statement = connection.createStatement()) {
                statement.execute("set plan_cache_mode = force_generic_plan");
                statement.execute("""
                        prepare effect_exists(text, uuid, bigint) as
                        select exists (select from read_availability_effects
                                       where family = $1 and projection_key = $2 and logical_version = $3)
                        """);
                statement.execute("""
                        prepare unversioned_effect_exists(text, uuid) as
                        select exists (select from read_availability_effects
                                       where family = $1 and projection_key = $2 and logical_version is null)
                        """);
                assertGenericPlanScansOnly(statement, """
                        execute effect_exists('CURRENT_INVITATION_CHANGED',
                                              '00000000-0000-0000-0000-000000000001', 1)
                        """, "read_current_invitation");
                assertGenericPlanScansOnly(statement, """
                        execute unversioned_effect_exists('IDENTITY_REQUEST_RESULT_AVAILABLE',
                                                          '00000000-0000-0000-0000-000000000001')
                        """, "read_identity_request_result");
            }
        }
    }

    private static void assertGenericPlanScansOnly(java.sql.Statement statement, String execute, String relation)
            throws java.sql.SQLException {
        var plan = new StringBuilder();
        try (var rows = statement.executeQuery("explain (analyze, costs off, timing off) " + execute)) {
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
        }
        var executedScans = plan.toString().lines()
                .filter(line -> line.contains(" on read_") && !line.contains("Bitmap Index Scan"))
                .filter(line -> line.contains("loops="))
                .toList();
        assertFalse(executedScans.isEmpty(), plan.toString());
        assertTrue(executedScans.stream().allMatch(line -> line.contains(" on " + relation + " ")),
                plan.toString());
        assertTrue(plan.toString().contains("One-Time Filter:")
                && plan.toString().contains("(never executed)"), plan.toString());
    }

    private static void enable(ReadParticipantsFixture fixture) {
        var db = fixture.f.read;
        install(db);
        for (var name : ReadParticipantsFixture.PROJECTIONS_WITH_SEEDED_EVENTS) {
            String capability = fixture.participants.get(name).participant().getClass().getSimpleName();
            var selected = ReadParticipants.select(capability, fixture.f.primary, fixture.f.sourceOwner, db.source, db.tx);
            fixture.engines.put(name, fixture.engine(name, selected.participant(), db.source, db.tx));
        }
    }

    private static void install(SubobjectPostgresFixture db) {
        assertEquals(1, Flyway.configure().dataSource(db.admin).schemas(db.schema).defaultSchema(db.schema)
                .createSchemas(false)
                .locations("classpath:db/read/migration", "classpath:db/read/availability/migration")
                .placeholders(Map.of("expectedDatabase", PG.getDatabaseName(), "expectedSchema", db.schema,
                        "expectedAdmin", db.adminRole, "expectedRuntime", db.runtimeRole))
                .load().migrate().migrationsExecuted);
    }

    private static void publishVisibility(SubobjectPostgresFixture db, JdbcPotVisibilityStore store, UUID pot, long version) {
        db.tx.write(() -> {
            writeVisibility(store, pot, version);
            return null;
        });
    }

    private static void writeVisibility(JdbcPotVisibilityStore store, UUID pot, long version) {
        var receipt = store.apply(new PotVisibility(pot, version, Set.of()));
        store.requireCompletionReady(receipt);
        store.closePublication(receipt);
    }

    private record Page(long head, long next, List<UUID> keys) {}

    private static Page page(JdbcTemplate query, long cursor, int size) {
        return query.queryForObject("""
                select head, next_cursor,
                       array(select (item->>'projection_key')::uuid from jsonb_array_elements(changes) item)
                from read_current_availability_page('POT_VISIBILITY_CHANGED', ?, ?)
                """, (row, index) -> new Page(row.getLong(1), row.getLong(2),
                List.of((UUID[]) row.getArray(3).getArray())), cursor, size);
    }

    private static long head(JdbcTemplate query, String family) {
        return query.queryForObject("select position from read_availability_counter where family = ?", Long.class, family);
    }

    private static String state(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        throw new AssertionError("Expected a PostgreSQL failure", failure);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "PostgreSQL test barrier timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
