package com.kartaguez.pocoma;
import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants;
import com.kartaguez.pocoma.infra.security.spring.InvitationConfiguration;
import com.kartaguez.pocoma.infra.security.spring.InvitationCryptography;
import com.kartaguez.pocoma.infra.postgres.source.*;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.*;
import com.kartaguez.pocoma.infra.postgres.core.consumption.*;
import com.kartaguez.pocoma.infra.postgres.core.history.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.Invitation;
import com.kartaguez.pocoma.infra.postgres.write.admission.*;
import com.kartaguez.pocoma.infra.postgres.write.pot.*;
import com.kartaguez.pocoma.infra.postgres.write.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

class InvitationReadPostgresTest extends InvitationFixture {
    @Test
    void invitationPublicationRejectsMissingUnknownAndWrongRequests() throws Exception {
        startPot();
        UUID original = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(original);
        var write = f.worker.write;
        UUID wrong =
                write.queryForObject(
                        "select request_id from v3_command where operation='CreatePot' and pot_id=?",
                        UUID.class,
                        f.pot);
        var principal =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        f.worker.writeUrl, f.worker.writeRole, "i5-fixture");
        for (String variant : List.of("missing", "unknown", "wrong")) {
            UUID request =
                    variant.equals("missing")
                            ? null
                            : variant.equals("unknown") ? UUID.randomUUID() : wrong;
            verifyInvitationRefusalVariant(write, principal, original, variant, request);
        }
    }

    private void verifyInvitationRefusalVariant(
            org.springframework.jdbc.core.JdbcTemplate write,
            DataSource principal,
            UUID original,
            String variant,
            UUID request)
            throws Exception {
        UUID invitation = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        var before = snapshotInvitationTables(write);
        java.sql.SQLException failure;
        try (var connection = principal.getConnection()) {
            connection.setAutoCommit(false);
            verifyWriteRoleIdentity(connection, variant);
            failure =
                    assertThrows(
                            java.sql.SQLException.class,
                            () -> insertRefusedInvitation(connection, invitation, request, original, event));
            connection.rollback();
        }
        String expectedState =
                switch (variant) {
                    case "missing" -> "23502";
                    case "unknown" -> "23503";
                    default -> "23514";
                };
        var after = snapshotInvitationTables(write);
        assertAll(
                () -> assertEquals(expectedState, failure.getSQLState(), variant),
                () ->
                        assertEquals(
                                before,
                                after,
                                variant + " must leave no partial effect or publication"),
                () ->
                        assertEquals(
                                0,
                                write.queryForObject(
                                        "select count(*) from v3_invitation where invitation_id=?",
                                        Integer.class,
                                        invitation)),
                () ->
                        assertEquals(
                                0,
                                write.queryForObject(
                                        "select count(*) from v3_invitation_event where event_id=?",
                                        Integer.class,
                                        event)));
        System.out.println(
                "Invitation refusal "
                        + variant
                        + " SQLSTATE="
                        + failure.getSQLState()
                        + " external snapshot unchanged="
                        + after);
    }

    private static Map<String, Object> snapshotInvitationTables(
            org.springframework.jdbc.core.JdbcTemplate write) {
        return write.queryForMap(
                "select (select count(*) from v3_invitation) invitations,"
                        + " (select count(*) from v3_invitation_event) events,"
                        + " (select count(*) from v3_event_position) positions,"
                        + " (select sum(position) from v3_event_counter) counters");
    }

    private void verifyWriteRoleIdentity(java.sql.Connection connection, String variant)
            throws Exception {
        try (var statement = connection.createStatement();
                var identity =
                        statement.executeQuery(
                                "select current_user,pg_backend_pid(),pg_current_xact_id()::text,"
                                        + " rolsuper,rolcreaterole,rolcreatedb from pg_roles where rolname=current_user")) {
            assertTrue(identity.next());
            assertAll(
                    () -> assertEquals(f.worker.writeRole, identity.getString(1)),
                    () -> assertFalse(identity.getBoolean(4)),
                    () -> assertFalse(identity.getBoolean(5)),
                    () -> assertFalse(identity.getBoolean(6)));
            System.out.println(
                    "Invitation refusal "
                            + variant
                            + " role="
                            + identity.getString(1)
                            + " pid="
                            + identity.getInt(2)
                            + " xid="
                            + identity.getString(3));
        }
    }

    private static void insertRefusedInvitation(
            java.sql.Connection connection,
            UUID invitation,
            UUID request,
            UUID original,
            UUID event)
            throws Exception {
        try (var insert =
                connection.prepareStatement(
                        "insert into v3_invitation"
                                + " (invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link)"
                                + " select ?,1,?,creator_issuer,creator_subject,pot_id,expires_at,?,protected_link"
                                + " from v3_invitation where creation_request_id=?")) {
            insert.setObject(1, invitation);
            insert.setObject(2, request);
            insert.setString(3, invitation.toString().replace("-", "").repeat(2));
            insert.setObject(4, original);
            assertEquals(1, insert.executeUpdate());
        }
        try (var insert =
                connection.prepareStatement(
                        "insert into v3_invitation_event"
                                + " (event_id,invitation_id,version,recorded_at) values (?,?,1,clock_timestamp())")) {
            insert.setObject(1, event);
            insert.setObject(2, invitation);
            assertEquals(1, insert.executeUpdate());
        }
        long position;
        try (var statement = connection.createStatement();
                var counter =
                        statement.executeQuery(
                                "update v3_event_counter set position=position+1 where family='INVITATION' returning position")) {
            assertTrue(counter.next());
            position = counter.getLong(1);
        }
        try (var insert =
                connection.prepareStatement(
                        "insert into v3_event_position(family,position,event_id) values ('INVITATION',?,?)")) {
            insert.setLong(1, position);
            insert.setObject(2, event);
            assertEquals(1, insert.executeUpdate());
        }
        connection.commit(); // The wrong existing request reaches the deferred unit check.
    }

    static SpringAttemptTransactions owner(DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(source, manager, 10);
    }

    Invitation source(UUID event) {
        var ds = f.worker.write.getDataSource();
        var owner = owner(ds);
        return owner.read(c -> new JdbcInvitationSource(ds).load(event, c));
    }

    private record RollbackPositions(long inviteBefore, long completionBefore, long potBefore) {}

    @Test
    void createInvitationRollsBackEffectsAndPositionsBeforeRetry() throws Exception {
        // Keep a confirmed failed attempt observable before the real retry acquires its slot.
        f.worker.properties.put("pocoma.v3.worker.lifecycle.retry-delay-ms", "5000");
        startPot();
        var write = f.worker.write;
        var read = f.worker.read;
        write.execute("""
                create function l7_invitation_rollback() returns trigger language plpgsql
                as $$ begin
                    raise exception 'L7 injected Invitation rollback: %', tg_table_name
                        using errcode = '40001';
                end $$;
                """);
        var stages =
                List.of(
                        "trigger l7_invitation_failure after insert on v3_invitation for each row",
                        "trigger l7_invitation_failure after insert on v3_event_position"
                                + " for each row when (new.family='INVITATION')",
                        "constraint trigger l7_invitation_failure after insert on v3_command_result"
                                + " deferrable initially deferred for each row"
                                + " when (new.outcome='INVITATION_CREATED')");
        var tables = List.of("v3_invitation", "v3_event_position", "v3_command_result");
        for (int stage = 0; stage < stages.size(); stage++) {
            verifyInvitationRollbackStage(write, read, stages.get(stage), tables.get(stage));
        }
    }

    private void verifyInvitationRollbackStage(
            org.springframework.jdbc.core.JdbcTemplate write,
            org.springframework.jdbc.core.JdbcTemplate read,
            String triggerDefinition,
            String table)
            throws Exception {
        String counterSql = "select position from v3_event_counter where family=?";
        var before =
                new RollbackPositions(
                        write.queryForObject(counterSql, Long.class, "INVITATION"),
                        write.queryForObject(counterSql, Long.class, "COMMAND_COMPLETION"),
                        write.queryForObject(counterSql, Long.class, "POT"));
        write.execute("create " + triggerDefinition + " execute function l7_invitation_rollback()");
        UUID request;
        try {
            request =
                    f.accepted(
                            f.post(
                                    "/commands",
                                    "owner",
                                    UUID.randomUUID().toString(),
                                    invitationBody(Instant.parse("2030-01-01T00:00:00Z"))));
            assertRolledBackInvitationState(write, read, request, table, before);
        } finally {
            write.execute("drop trigger l7_invitation_failure on " + table);
        }
        assertEquals(
                "COMPLETED",
                f.poll("/commands/" + request + "/result", "owner", null).get("outcome").asText());
        link(request); // The projected Invitation must also be available for this creation.
        assertRetriedInvitationPositions(write, request, table, before);
    }

    private static void assertRolledBackInvitationState(
            org.springframework.jdbc.core.JdbcTemplate write,
            org.springframework.jdbc.core.JdbcTemplate read,
            UUID request,
            String table,
            RollbackPositions before)
            throws Exception {
        ReadMonolithI8Fixture.await(
                () ->
                        write.queryForObject(
                                """
                                select exists(select 1 from consumption_slot
                                 where consumable_id=? and state='AVAILABLE' and claim_token is null
                                   and attempt_count=1 and retry_after>clock_timestamp())
                                """,
                                Boolean.class,
                                request));
        var rollback =
                write.queryForMap(
                        """
                        select c.status, s.state, s.attempt_count, s.claim_token is null unclaimed,
                            (select count(*) from v3_invitation i where i.creation_request_id=c.request_id) invitations,
                            (select count(*) from v3_command_result r where r.request_id=c.request_id) results,
                            (select count(*) from v3_command_completion_event e where e.subject_id=c.request_id) completions,
                            (select count(*) from v3_pot_event e where e.request_id=c.request_id) pot_events,
                            (select position from v3_event_counter where family='INVITATION') invitation_position,
                            (select position from v3_event_counter where family='COMMAND_COMPLETION') completion_position,
                            (select position from v3_event_counter where family='POT') pot_position
                        from v3_command c join consumption_slot s on s.consumable_id=c.request_id
                        where c.request_id=?
                        """,
                        request);
        assertAll(
                () -> assertEquals("PENDING", rollback.get("status")),
                () -> assertEquals("AVAILABLE", rollback.get("state")),
                () -> assertEquals(1, rollback.get("attempt_count")),
                () -> assertEquals(true, rollback.get("unclaimed")),
                () -> assertEquals(0L, rollback.get("invitations")),
                () -> assertEquals(0L, rollback.get("results")),
                () -> assertEquals(0L, rollback.get("completions")),
                () -> assertEquals(0L, rollback.get("pot_events")),
                () -> assertEquals(before.inviteBefore(), rollback.get("invitation_position")),
                () ->
                        assertEquals(
                                before.completionBefore(), rollback.get("completion_position")),
                () -> assertEquals(before.potBefore(), rollback.get("pot_position")),
                () ->
                        assertEquals(
                                0L,
                                read.queryForObject(
                                        "select count(*) from read_command_result where request_id=?",
                                        Long.class,
                                        request)));
        System.out.println("Invitation rollback after " + table + ": " + rollback);
    }

    private static void assertRetriedInvitationPositions(
            org.springframework.jdbc.core.JdbcTemplate write,
            UUID request,
            String table,
            RollbackPositions before) {
        var positions =
                write.queryForMap(
                        """
                        select i.creation_request_id,
                               ip.position invitation_position, cp.position completion_position
                        from v3_invitation i join v3_invitation_event ie using(invitation_id,version)
                        join v3_event_position ip on ip.event_id=ie.event_id and ip.family='INVITATION'
                        join v3_command_completion_event ce on ce.subject_id=i.creation_request_id
                        join v3_event_position cp on cp.event_id=ce.event_id and cp.family='COMMAND_COMPLETION'
                        where i.creation_request_id=?
                        """,
                        request);
        assertAll(
                () -> assertEquals(request, positions.get("creation_request_id")),
                () ->
                        assertEquals(
                                before.inviteBefore() + 1, positions.get("invitation_position")),
                () ->
                        assertEquals(
                                before.completionBefore() + 1,
                                positions.get("completion_position")),
                () ->
                        assertEquals(
                                before.potBefore(),
                                write.queryForObject(
                                        "select position from v3_event_counter where family='POT'",
                                        Long.class)),
                () ->
                        assertEquals(
                                0L,
                                write.queryForObject(
                                        "select count(*) from consumption_slot where consumable_id=?",
                                        Long.class,
                                        request)));
        System.out.println(
                "Invitation retry after "
                        + table
                        + ": positions="
                        + positions.get("invitation_position")
                        + "/"
                        + positions.get("completion_position")
                        + " and slot released");
    }

    @Test
    void authenticatedEncryptionAndOldKeyRestoration() throws Exception {
        startPot();
        UUID id = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(id);
        var i =
                source(
                        f.worker.write.queryForObject(
                                "select event_id from v3_invitation_event", UUID.class));
        byte[] old =
                Base64.getDecoder()
                        .decode(
                                f.http.context
                                        .getEnvironment()
                                        .getRequiredProperty("pocoma.v3.invitation.keys.fixture"));
        byte[] fresh = new byte[32];
        new java.security.SecureRandom().nextBytes(fresh);
        var previous = new InvitationCryptography("fixture", Map.of("fixture", old));
        var restored = new InvitationCryptography("new", Map.of("fixture", old, "new", fresh));
        assertTrue(
                previous.decrypt(i).equals(restored.decrypt(i)),
                "Old protected link did not restore");
        var missingOld = new InvitationCryptography("new", Map.of("new", fresh));
        assertThrows(IllegalStateException.class, () -> missingOld.decrypt(i));
        var substituted =
                new Invitation(
                        UUID.randomUUID(),
                        i.version(),
                        i.creationRequestId(),
                        i.creatorIssuer(),
                        i.creatorSubject(),
                        i.potId(),
                        i.expiresAt(),
                        i.tokenHash(),
                        i.protectedLink());
        assertThrows(IllegalStateException.class, () -> restored.decrypt(substituted));
        String cipher = i.protectedLink();
        int index = cipher.lastIndexOf('.') + 2;
        var corrupt =
                new Invitation(
                        i.invitationId(),
                        i.version(),
                        i.creationRequestId(),
                        i.creatorIssuer(),
                        i.creatorSubject(),
                        i.potId(),
                        i.expiresAt(),
                        i.tokenHash(),
                        cipher.substring(0, index)
                                + (cipher.charAt(index) == 'A' ? 'B' : 'A')
                                + cipher.substring(index + 1));
        assertThrows(IllegalStateException.class, () -> restored.decrypt(corrupt));
        assertNull(
                f.worker
                        .contexts
                        .get(1)
                        .getEnvironment()
                        .getProperty("pocoma.v3.invitation.current-key"),
                "Projection root has no decryption key configuration");
    }

    @Test
    void monotoneDuplicateAndDivergence() throws Exception {
        startPot();
        UUID id = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(id);
        UUID event =
                f.worker.write.queryForObject(
                        "select event_id from v3_invitation_event", UUID.class);
        var first = source(event);
        var ds = f.worker.read.getDataSource();
        var owner = owner(ds);
        var store = new com.kartaguez.pocoma.infra.postgres.read.JdbcInvitationStore(ds);
        var crypto = InvitationConfiguration.keys(f.http.context.getEnvironment());
        var unsealed =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt(),
                        first.tokenHash(),
                        "unsealed");
        var next =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt(),
                        first.tokenHash(),
                        crypto.encrypt(unsealed, crypto.decrypt(first)));
        owner.write(
                () -> {
                    store.put(next);
                    return null;
                });
        owner.write(
                () -> {
                    store.put(first);
                    store.put(next);
                    return null;
                });
        assertEquals(next, owner.read(c -> store.byRequest(id).orElseThrow()));
        assertTrue(crypto.decrypt(first).equals(crypto.decrypt(next)), "Protected link changed");
        var divergent =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt().plusSeconds(1),
                        first.tokenHash(),
                        next.protectedLink());
        assertThrows(
                RuntimeException.class,
                () ->
                        owner.write(
                                () -> {
                                    store.put(divergent);
                                    return null;
                                }));
        assertEquals(next, owner.read(c -> store.byRequest(id).orElseThrow()));
    }

    @Test
    void reconstructOnFreshReadWithoutProducer() throws Exception {
        startPot();
        UUID a = create(Instant.parse("2030-01-01T00:00:00Z"));
        UUID b = create(Instant.parse("2031-01-01T00:00:00Z"));
        link(a);
        link(b);
        var expected =
                f.worker.read.queryForList(
                        "select"
                                + " invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link"
                                + " from read_current_invitation order by invitation_id");
        for (var context : new ArrayList<>(f.worker.contexts)) {
            f.worker.drain(context);
        }
        try (var fresh = new ReadMonolithI8Fixture(PG)) {
            migrateFreshReadSchema(fresh);
            UUID definitionId = replayInvitationsOnFreshRead(fresh);
            assertAll(
                    () ->
                            assertEquals(
                                    expected,
                                    fresh.read.queryForList(
                                            "select"
                                                    + " invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link"
                                                    + " from read_current_invitation order by invitation_id")),
                    () ->
                            assertEquals(
                                    0,
                                    fresh.read.queryForObject(
                                            "select count(*) from consumption_slot where consumption_id=?",
                                            Integer.class,
                                            definitionId)),
                    () ->
                            assertEquals(
                                    4,
                                    fresh.read.queryForObject(
                                            "select count(*) from discovery_checkpoint where consumption_id=?",
                                            Integer.class,
                                            definitionId)));
        }
    }

    private static void migrateFreshReadSchema(ReadMonolithI8Fixture fresh) {
        Flyway.configure()
                .dataSource(fresh.readUrl, PG.getUsername(), PG.getPassword())
                .schemas(fresh.schema)
                .defaultSchema(fresh.schema)
                .locations("classpath:db/read/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                "i5_read",
                                "expectedSchema",
                                fresh.schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                fresh.readRole))
                .load()
                .migrate();
        assertEquals(
                0,
                fresh.read.queryForObject(
                        "select count(*) from read_current_invitation", Integer.class));
    }

    private UUID replayInvitationsOnFreshRead(ReadMonolithI8Fixture fresh) {
        var write = f.worker.write.getDataSource();
        var read = fresh.read.getDataSource();
        var sourceOwner = owner(write);
        var effects = owner(read);
        var participant = ReadParticipants.invitation(write, read);
        var definition = participant.definition();
        var settings = LifecycleSettings.pilot();
        var discoverySettings =
                new DiscoverySettings(
                        128, 32, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
        var slots = new JdbcConsumptionSlotStore(read);
        var ownership = new JdbcPartitionOwnershipStore(read);
        var admissions = new JdbcDiscoveryAdmissionStore(read, discoverySettings);
        var source =
                new JdbcEventDiscoveryReferences(write, definition.family(), discoverySettings);
        var engine =
                new ConsumptionEngine(
                        definition,
                        effects,
                        slots,
                        participant.participant(),
                        sourceOwner,
                        Optional.of(source),
                        r -> true,
                        admissions,
                        effects,
                        slots,
                        ownership,
                        settings,
                        new WorkerCapacity(1, Map.of(definition.id(), 1)),
                        failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                        null,
                        ConsumptionObservation.noop());
        var discovery =
                new DiscoveryEngine(
                        definition,
                        effects,
                        ownership,
                        admissions,
                        discoverySettings,
                        sourceOwner,
                        source,
                        r -> true);
        for (int p = 3; p >= 0; p--) {
            replayInvitationPartition(fresh, engine, discovery, definition.id().value(), p);
        }
        return definition.id().value();
    }

    private static void replayInvitationPartition(
            ReadMonolithI8Fixture fresh,
            ConsumptionEngine engine,
            DiscoveryEngine discovery,
            UUID definitionId,
            int partition) {
        var lease = engine.acquirePartition(partition).orElseThrow();
        discovery.discoverEvents(lease);
        var ids =
                fresh.read.queryForList(
                        "select consumable_id from consumption_slot where consumption_id=?"
                                + " and partition_id=? order by consumable_id desc",
                        UUID.class,
                        definitionId,
                        partition);
        for (UUID id : ids) {
            var claims = engine.acquire(lease, List.of(id)).claims();
            assertEquals(1, claims.size());
            var attempt = engine.execute(claims.getFirst());
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED,
                    attempt.status(),
                    () -> "Replay: " + attempt.cause());
        }
        discovery.discoverEvents(lease);
    }
}
