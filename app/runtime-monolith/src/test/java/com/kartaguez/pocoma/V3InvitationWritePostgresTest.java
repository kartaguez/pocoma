package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

class V3InvitationWritePostgresTest extends V3InvitationFixture {
    @Test
    void atomicCreationAndIdempotence() throws Exception {
        startPot();
        long version = f.version;
        Object body =
                invitationBody(
                        Instant.now()
                                .plusSeconds(60)
                                .truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        UUID id = f.accepted(f.post("/commands", "owner", "create", body));
        var result = f.poll("/commands/" + id + "/result", "owner", null);
        assertEquals("COMPLETED", result.get("outcome").asText());
        String secret = invitationToken(id);
        assertEquals(id, f.accepted(f.post("/commands", "owner", "create", body)));
        assertEquals(
                409,
                f.post(
                                "/commands",
                                "owner",
                                "create",
                                invitationBody(Instant.parse("2030-01-01T00:00:00Z")))
                        .statusCode());
        assertEquals(
                version,
                f.worker.write.queryForObject(
                        "select current_version from v3_pot_identity where pot_id=?",
                        Long.class,
                        f.pot));
        assertEquals(
                1,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
        assertEquals(
                0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_pot_event where request_id=?", Integer.class, id));
        assertEquals(
                1,
                f.worker.write.queryForObject(
                        "select count(*) from v3_invitation_event e join v3_event_position p on"
                                + " e.event_id=p.event_id and p.family='INVITATION'",
                        Integer.class));
        for (String table :
                List.of(
                        "v3_command",
                        "v3_command_result",
                        "v3_invitation",
                        "v3_invitation_event",
                        "v3_command_completion_event"))
            assertFalse(
                    f.worker
                            .write
                            .queryForList(
                                    "select to_jsonb(t)::text from " + table + " t", String.class)
                            .toString()
                            .contains(secret),
                    table);
        assertFalse(result.toString().contains(secret));
        assertNull(
                f.worker.write.queryForObject(
                        "select resulting_pot_version from v3_command_result where request_id=?",
                        Long.class,
                        id));
        var source = f.worker.write.getDataSource();
        var owner = V3InvitationReadPostgresTest.owner(source);
        assertEquals(
                com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation.COMPLETED,
                owner.read(
                        c ->
                                new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                .JdbcPotCommandObservation(source)
                                        .observe(id, c)));
    }

    @Test
    void creatorOnlyAndExpiredCreation() throws Exception {
        startPot();
        String owner = f.token;
        f.token = binding("other");
        UUID forbidden =
                f.accepted(
                        f.post(
                                "/commands",
                                "other",
                                "forbidden",
                                invitationBody(Instant.parse("2030-01-01T00:00:00Z"))));
        assertEquals(
                "FORBIDDEN",
                f.poll("/commands/" + forbidden + "/result", "other", null)
                        .get("rejection")
                        .asText());
        f.token = owner;
        UUID expired =
                f.accepted(
                        f.post(
                                "/commands",
                                "owner",
                                "expired",
                                invitationBody(Instant.parse("2000-01-01T00:00:00Z"))));
        assertEquals(
                "INVALID_INVITATION",
                f.poll("/commands/" + expired + "/result", "owner", null)
                        .get("rejection")
                        .asText());
        assertEquals(
                0,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
        assertEquals(
                0L,
                f.worker.write.queryForObject(
                        "select position from v3_event_counter where family='INVITATION'",
                        Long.class));
        assertEquals(
                400,
                f.post("/commands", "owner", "too-large", invitationBody(Instant.MAX))
                        .statusCode());
    }

    @Test
    void twoGuestsOnePlaceAndReusableToken() throws Exception {
        startPot();
        String b = binding("guest-b"), c = binding("guest-c");
        String token =
                invitationToken(
                        create(
                                Instant.now()
                                        .plusSeconds(60)
                                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        try (var gate = f.worker.write.getDataSource().getConnection();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            gate.setAutoCommit(false);
            try (var s =
                    gate.prepareStatement(
                            "select current_version from v3_pot_identity where pot_id=? for"
                                    + " update")) {
                s.setObject(1, f.pot);
                s.execute();
            }
            UUID left =
                    f.accepted(
                            f.post(
                                    "/commands",
                                    "guest-b",
                                    "race-left",
                                    attachBody(b, token, f.share)));
            UUID right =
                    f.accepted(
                            f.post(
                                    "/commands",
                                    "guest-c",
                                    "race-right",
                                    attachBody(c, token, f.share)));
            awaitTwoFinalWriteWaiters();
            gate.commit();
            var a = threads.submit(() -> f.poll("/commands/" + left + "/result", "guest-b", null));
            var d = threads.submit(() -> f.poll("/commands/" + right + "/result", "guest-c", null));
            var x = a.get(25, TimeUnit.SECONDS);
            var y = d.get(25, TimeUnit.SECONDS);
            assertNotEquals(x.get("outcome").asText(), y.get("outcome").asText());
            assertEquals(
                    "INVALID_ATTACHMENT",
                    (x.get("outcome").asText().equals("REJECTED") ? x : y)
                            .get("rejection")
                            .asText());
            String loser = x.get("outcome").asText().equals("REJECTED") ? "guest-b" : "guest-c";
            assertEquals(
                    "COMPLETED",
                    attach(loser, loser.equals("guest-b") ? b : c, token, new UUID(4, 2), "reuse")
                            .get("outcome")
                            .asText());
        }
        assertEquals(
                2,
                f.worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where active_to is null and"
                                + " user_id is not null",
                        Integer.class));
        assertEquals(
                1,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
    }

    @Test
    void oneGuestCannotTakeTwoPlaces() throws Exception {
        startPot();
        String b = binding("guest");
        String token =
                invitationToken(
                        create(
                                Instant.now()
                                        .plusSeconds(60)
                                        .truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
        UUID a, c;
        try (var gate = f.worker.write.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var statement =
                    gate.prepareStatement(
                            "select current_version from v3_pot_identity where pot_id=? for"
                                + " update")) {
                statement.setObject(1, f.pot);
                statement.execute();
            }
            a = f.accepted(f.post("/commands", "guest", "a", attachBody(b, token, f.share)));
            c = f.accepted(f.post("/commands", "guest", "b", attachBody(b, token, new UUID(4, 2))));
            awaitTwoFinalWriteWaiters();
            gate.commit();
        }
        var x = f.poll("/commands/" + a + "/result", "guest", null);
        var y = f.poll("/commands/" + c + "/result", "guest", null);
        assertNotEquals(x.get("outcome").asText(), y.get("outcome").asText());
        assertEquals(
                1,
                f.worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where active_to is null and"
                                + " user_id is not null",
                        Integer.class));
    }

    @Test
    void rollbackAfterInvitationPositionKeepsDensePublication() throws Exception {
        startPot();
        f.worker.write.execute("create sequence i4_fail_once");
        f.worker.write.execute(
                "create function i4_fail_completion() returns trigger language plpgsql as $$ begin"
                    + " if new.family='COMMAND_COMPLETION' and nextval('i4_fail_once')=1 then raise"
                    + " exception 'I4 injected suffix rollback'; end if; return new; end $$");
        f.worker.write.execute(
                "create trigger i4_fail_completion before insert on v3_event_position for each row"
                        + " execute function i4_fail_completion()");
        UUID id =
                f.accepted(
                        f.post(
                                "/commands",
                                "owner",
                                "rollback",
                                invitationBody(Instant.parse("2030-01-01T00:00:00Z"))));
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.write.queryForObject(
                                "select coalesce(max(attempt_count),0)>0 from consumption_slot"
                                        + " where consumable_id=?",
                                Boolean.class,
                                id));
        assertEquals(
                0,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
        assertEquals(
                0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_invitation_event", Integer.class));
        assertEquals(
                0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_command_result where request_id=?",
                        Integer.class,
                        id));
        assertEquals(
                0L,
                f.worker.write.queryForObject(
                        "select position from v3_event_counter where family='INVITATION'",
                        Long.class));
        assertEquals(
                0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_event_position where family='INVITATION'",
                        Integer.class));
        assertEquals(
                "PENDING",
                f.worker.write.queryForObject(
                        "select status from v3_command where request_id=?", String.class, id));
        f.worker.write.execute("drop trigger i4_fail_completion on v3_event_position");
        // Keep the existing retry policy/deadline. No technical state is rewritten to force
        // success.
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.write.queryForObject(
                                "select status='TERMINAL' from v3_command where request_id=?",
                                Boolean.class,
                                id),
                Duration.ofSeconds(45));
        assertEquals(
                "COMPLETED",
                f.poll("/commands/" + id + "/result", "owner", null).get("outcome").asText());
        assertEquals(
                1L,
                f.worker.write.queryForObject(
                        "select position from v3_event_counter where family='INVITATION'",
                        Long.class));
        assertEquals(
                1L,
                f.worker.write.queryForObject(
                        "select min(position) from v3_event_position where family='INVITATION'",
                        Long.class));
        assertEquals(
                1,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
    }

    @Test
    void expirationAfterPublicationCounterWait() throws Exception {
        startPot();
        String guest = binding("guest");
        // Expiry is established by the database, with ample headroom to complete
        // admission, initial verification, and enter the lock wait on v3_event_counter.
        Instant expiry =
                f.worker
                        .write
                        .queryForObject(
                                "select clock_timestamp()+interval '4 seconds'",
                                java.sql.Timestamp.class)
                        .toInstant();
        UUID invitation = create(expiry);
        String secret = invitationToken(invitation);
        try (var gate = f.worker.write.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            gate.createStatement()
                    .execute(
                            "select position from v3_event_counter where"
                                    + " family='COMMAND_COMPLETION' for update");
            UUID id =
                    f.accepted(
                            f.post(
                                    "/commands",
                                    "guest",
                                    "expired-during-suffix",
                                    attachBody(guest, secret, f.share)));
            ReadMonolithI8Fixture.await(
                    () ->
                            Boolean.TRUE.equals(
                                    f.worker.write.queryForObject(
                                            "select exists(select 1 from pg_stat_activity a where"
                                                + " datname=current_database() and"
                                                + " wait_event_type='Lock' and query like"
                                                + " '%v3_event_counter%' and exists(select 1 from"
                                                + " pg_locks l where l.pid=a.pid and l.granted and"
                                                + " l.mode='RowExclusiveLock' and"
                                                + " l.relation='v3_pot_version'::regclass))",
                                            Boolean.class)));
            ReadMonolithI8Fixture.await(
                    () ->
                            f.worker.write.queryForObject(
                                    "select clock_timestamp()>=?",
                                    Boolean.class,
                                    java.sql.Timestamp.from(expiry)));
            gate.commit();
            var result = f.poll("/commands/" + id + "/result", "guest", null);
            assertEquals("REJECTED", result.get("outcome").asText());
            assertEquals("INVALID_INVITATION", result.get("rejection").asText());
            assertTrue(
                    PG.getLogs().contains("Invitation expired before final commit"),
                    "Deferred final expiry must execute");
            assertNull(
                    f.worker.write.queryForObject(
                            "select user_id from v3_shareholder_details where object_id=? and"
                                    + " active_to is null",
                            UUID.class,
                            f.share));
            assertEquals(
                    f.version,
                    f.worker.write.queryForObject(
                            "select current_version from v3_pot_identity where pot_id=?",
                            Long.class,
                            f.pot));
            assertEquals(
                    0,
                    f.worker.write.queryForObject(
                            "select count(*) from v3_pot_event where request_id=?",
                            Integer.class,
                            id));
        }
    }
}
