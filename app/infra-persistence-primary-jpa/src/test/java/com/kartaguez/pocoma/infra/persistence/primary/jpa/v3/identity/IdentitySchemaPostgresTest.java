package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class IdentitySchemaPostgresTest extends IdentityPostgresFixture {
    @Test
    void l3_sql_negative_shapes() throws Exception {
        assertEquals(schema + "_runtime", jdbc.queryForObject("select current_user", String.class));
        assertFalse(
                jdbc.queryForObject(
                        "select rolsuper from pg_roles where rolname=current_user", Boolean.class));
        assertFalse(
                jdbc.queryForObject(
                        "select has_table_privilege(current_user,'v3_eui_version','TRUNCATE')",
                        Boolean.class));
        assertFalse(
                jdbc.queryForObject(
                        "select has_schema_privilege(current_user,current_schema(),'CREATE')",
                        Boolean.class));
        var w = admit(registration(key("seed")));
        var state = changed(run(w)).identity();
        var pending = admit(registration(key("pending")));
        sqlFail(
                "v3_identity_anchor_key",
                "insert into v3_eui_authority(eui_id,issuer,subject) values ('"
                        + UUID.randomUUID()
                        + "','i2-controlled','seed')");
        sqlFail(
                "v3_identity_writer_xid",
                "insert into v3_pocoma_user values ('"
                        + UUID.randomUUID()
                        + "','"
                        + pending.id()
                        + "','1'::xid8)");
        sqlFail(
                "v3_identity_binding_pair",
                "insert into v3_eui_version(eui_id,version,user_id,request_id) values ('"
                        + state.id().value()
                        + "',2,'"
                        + state.userId().value()
                        + "','"
                        + pending.id()
                        + "')");
        sqlFail(
                "v3_identity_successor",
                "insert into v3_eui_version(eui_id,version,request_id) values ('"
                        + state.id().value()
                        + "',4,'"
                        + pending.id()
                        + "')");
        sqlFail(
                "v3_identity_user_request_fk",
                "insert into v3_pocoma_user(user_id,created_request_id) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + UUID.randomUUID()
                        + "')");
        sqlFail(
                "v3_identity_result_shape",
                "insert into v3_identity_request_result(request_id,outcome) values ('"
                        + pending.id()
                        + "','REJECTED')");
        sqlFail(
                "v3_identity_request_guard",
                "update v3_identity_request set target_subject='other' where request_id='"
                        + pending.id()
                        + "'");
        sqlFail(
                "v3_identity_admission_shape",
                "insert into v3_identity_request select '"
                        + UUID.randomUUID()
                        + "',requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,'{}'::jsonb,created_at,'PENDING'"
                        + " from v3_identity_request where request_id='"
                        + pending.id()
                        + "'");
        sqlFail(
                "v3_identity_admission_shape",
                "insert into v3_identity_request select '"
                        + UUID.randomUUID()
                        + "',requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,jsonb_set(admission_context,'{target}','{\"issuer\":\"wrong\",\"subject\":\"wrong\"}'::jsonb),created_at,'PENDING'"
                        + " from v3_identity_request where request_id='"
                        + pending.id()
                        + "'");
        sqlFail(
                "v3_identity_unit",
                "insert into v3_pocoma_user(user_id,created_request_id) values ('"
                        + UUID.randomUUID()
                        + "','"
                        + pending.id()
                        + "')");
        sqlFail(
                "v3_identity_unit",
                "update v3_identity_request set status='TERMINAL' where request_id='"
                        + pending.id()
                        + "'");
    }

    @Test
    void l3_history_rewrite_forbidden() throws Exception {
        var w = admit(registration(key("seed")));
        run(w);
        for (var entry :
                Map.of(
                                "v3_pocoma_user",
                                "v3_identity_user_immutable",
                                "v3_eui_version",
                                "v3_identity_version_immutable",
                                "v3_identity_request_result",
                                "v3_identity_result_immutable",
                                "v3_identity_event",
                                "v3_identity_event_immutable",
                                "v3_identity_completion_event",
                                "v3_identity_completion_immutable",
                                "v3_event_position",
                                "v3_position_immutable")
                        .entrySet()) {
            String column =
                    entry.getKey().equals("v3_pocoma_user")
                            ? "user_id"
                            : entry.getKey().equals("v3_eui_version")
                                    ? "version"
                                    : entry.getKey().equals("v3_identity_request_result")
                                            ? "request_id"
                                            : entry.getKey().equals("v3_event_position")
                                                    ? "position"
                                                    : "event_id";
            sqlFail(entry.getValue(), "update " + entry.getKey() + " set " + column + "=" + column);
            sqlFail(entry.getValue(), "delete from " + entry.getKey());
        }
        sqlFail("v3_identity_request_guard", "delete from v3_identity_request");
        sqlFail("v3_identity_request_guard", "update v3_identity_request set status='PENDING'");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            assertEquals(
                    "42501",
                    assertThrows(
                                    SQLException.class,
                                    () -> s.execute("truncate v3_eui_version cascade"))
                            .getSQLState());
        }
    }

    @Test
    void l3_anchor_negative_invariants() throws Exception {
        var state = changed(run(admit(registration(key("seed"))))).identity();
        sqlFail(
                "v3_identity_anchor_pk",
                "insert into v3_eui_authority(eui_id,issuer,subject) values ('"
                        + state.id().value()
                        + "','different','key')");
        sqlFail(
                "v3_identity_anchor_guard",
                "update v3_eui_authority set eui_id='" + UUID.randomUUID() + "'");
        sqlFail("v3_identity_anchor_guard", "update v3_eui_authority set issuer='changed'");
        sqlFail("v3_identity_anchor_guard", "update v3_eui_authority set subject='changed'");
        sqlFail("v3_identity_anchor_guard", "delete from v3_eui_authority");
        sqlFail("v3_identity_anchor_guard", "update v3_eui_authority set current_version=0");
        sqlFail(
                "v3_identity_anchor_guard",
                "insert into v3_eui_authority values ('" + UUID.randomUUID() + "','i','s',1)");
        sqlFail("v3_identity_anchor_state", "update v3_eui_authority set current_version=2");
        var pending = admit(registration(key("pending")));
        sqlFail(
                "v3_identity_successor",
                "insert into v3_eui_version(eui_id,version,request_id) values ('"
                        + state.id().value()
                        + "',0,'"
                        + pending.id()
                        + "')");
        UUID zero = UUID.randomUUID();
        jdbc.update(
                "insert into v3_eui_authority(eui_id,issuer,subject) values (?,'zero','zero')",
                zero);
        sqlFail(
                "v3_identity_initial_binding",
                "insert into v3_eui_version(eui_id,version,request_id) values ('"
                        + zero
                        + "',1,'"
                        + pending.id()
                        + "')");
        sqlFail(
                "v3_identity_version_state",
                "insert into v3_eui_version(eui_id,version,user_id,binding_token,request_id) values"
                        + " ('"
                        + zero
                        + "',1,'"
                        + state.userId().value()
                        + "',repeat('A',43),'"
                        + pending.id()
                        + "')",
                STRUCTURAL);
        sqlFail(
                "v3_identity_result_target_fk",
                "insert into v3_identity_request_result values ('"
                        + pending.id()
                        + "','CHANGED','"
                        + zero
                        + "',1,null)");
        sqlFail(
                "v3_identity_unit",
                "insert into v3_identity_event values ('"
                        + UUID.randomUUID()
                        + "','EXTERNAL_USER_IDENTITY','"
                        + zero
                        + "',1,'"
                        + pending.id()
                        + "',clock_timestamp())");
    }

    @Test
    void l3_sql_publication_closure() {
        for (String corruption :
                List.of(
                        "missing",
                        "wrong-family",
                        "pot-family",
                        "wrong-subject",
                        "extra-event",
                        "rejected-effect",
                        "slot-left",
                        "request-target")) {
            var w = admit(registration(key(corruption)));
            var before = vector();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            tx.write(
                                    () -> {
                                        var staged = stageRegistration(w, corruption);
                                        if (!corruption.equals("missing")) {
                                            position(
                                                    corruption.equals("wrong-family")
                                                            ? "IDENTITY_COMPLETION"
                                                            : corruption.equals("pot-family")
                                                                    ? "POT"
                                                                    : "IDENTITY",
                                                    List.of(staged.userEvent(), staged.euiEvent()));
                                            position(
                                                    "IDENTITY_COMPLETION",
                                                    List.of(staged.completion()));
                                        }
                                        return null;
                                    }),
                    corruption);
            assertEquals(before, vector(), corruption);
            assertPendingWithSlot(w.id());
        }
    }

    @Test
    void l3_position_constraints() throws Exception {
        sqlFail(
                "v3_identity_position_step",
                "update v3_event_counter set position=2 where family='IDENTITY'",
                "insert into v3_event_position values ('IDENTITY',2,'" + UUID.randomUUID() + "')");
        sqlFail(
                "v3_identity_position_unit",
                "update v3_event_counter set position=1 where family='IDENTITY'",
                "insert into v3_event_position values ('IDENTITY',1,'" + UUID.randomUUID() + "')");
        sqlFail(
                "v3_identity_counter_unit",
                "update v3_event_counter set position=1 where family='IDENTITY'");
        sqlFail(
                "v3_identity_counter_guard",
                "delete from v3_event_counter where family='IDENTITY'");
        sqlFail(
                "v3_identity_counter_guard",
                "update v3_event_counter set position=0 where family='IDENTITY'");
    }

    @Test
    void l3_constraint_catalog() {
        assertCatalog();
        evidence.add(
                "FK catalog="
                        + admin.queryForList(
                                "select conname,condeferrable,condeferred,pg_get_constraintdef(oid)"
                                        + " definition from pg_constraint where"
                                        + " connamespace=current_schema()::regnamespace and conname"
                                        + " like 'v3_identity_%' order by conname"));
        evidence.add(
                "trigger catalog="
                        + admin.queryForList(
                                "select tgname,tgdeferrable,tginitdeferred,pg_get_triggerdef(oid)"
                                        + " definition from pg_trigger where tgrelid in (select oid"
                                        + " from pg_class where"
                                        + " relnamespace=current_schema()::regnamespace) and tgname"
                                        + " like 'v3_identity_%' order by tgname"));
        for (String f : terminalFunctions())
            evidence.add(
                    "terminal function "
                            + f
                            + "="
                            + admin.queryForObject(
                                    "select prosrc from pg_proc where"
                                            + " pronamespace=current_schema()::regnamespace and"
                                            + " proname=?",
                                    String.class,
                                    f));
        admin.execute(
                "alter table v3_identity_event add constraint fixture_deferred_business_fk foreign"
                        + " key(subject_id) references v3_pocoma_user(user_id) deferrable initially"
                        + " deferred");
        assertThrows(AssertionError.class, this::assertCatalog);
        admin.execute("alter table v3_identity_event drop constraint fixture_deferred_business_fk");
        admin.execute(
                "create function fixture_bad_terminal() returns trigger language plpgsql as $$"
                        + " begin perform 1 from v3_pocoma_user for update; return new; end $$");
        admin.execute(
                "create constraint trigger fixture_bad_terminal after insert on v3_identity_event"
                        + " deferrable initially deferred for each row execute function"
                        + " fixture_bad_terminal()");
        assertThrows(AssertionError.class, this::assertCatalog);
    }

    @Test
    void l3_unstorable_keys() throws Exception {
        for (String bad : List.of("bad\0", "\ud800", "\udc00"))
            for (boolean issuer : List.of(false, true)) {
                int calls = lockCalls.size();
                var before = vector();
                assertThrows(
                        RuntimeException.class,
                        () ->
                                tx.write(
                                        () ->
                                                authority.lockInCanonicalOrder(
                                                        List.of(
                                                                key("valid-first"),
                                                                new ExternalIdentity(
                                                                        issuer ? bad : "valid",
                                                                        issuer ? "valid" : bad)))));
                assertEquals(calls, lockCalls.size());
                assertEquals(before, vector());
            }
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            assertEquals(
                    "22021",
                    assertThrows(
                                    SQLException.class,
                                    () ->
                                            s.execute(
                                                    "select"
                                                        + " convert_from(decode('c080','hex'),'UTF8')"))
                            .getSQLState());
        }
        assertEquals("UTF8", jdbc.queryForObject("show server_encoding", String.class));
        assertEquals("UTF8", jdbc.queryForObject("show client_encoding", String.class));
        assertEncodingAndCollation();
        admin.execute(
                "alter table v3_eui_authority alter column subject type text collate \"POSIX\"");
        assertThrows(AssertionError.class, this::assertEncodingAndCollation);
        admin.execute("alter table v3_eui_authority alter column subject type text collate \"C\"");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            assertEquals(
                    "08006",
                    assertThrows(
                                    SQLException.class,
                                    () -> s.execute("set client_encoding='LATIN1'"))
                            .getSQLState());
            evidence.add(
                    "JDBC rejected non-UTF8 client encoding with SQLSTATE 08006 before any EUI"
                            + " write");
        }
    }

    @Test
    void l3_fk_wait_is_in_prefix() throws Exception {
        var e = key("initiator");
        var a = changed(run(admit(registration(e)))).identity();
        var w = admit(link(e, a.bindingToken(), key("target")));
        var entered = new CountDownLatch(1);
        var pid = new AtomicInteger();
        try (var block = source.getConnection()) {
            block.setAutoCommit(false);
            int blocker = block.unwrap(org.postgresql.PGConnection.class).getBackendPID();
            try (var s =
                    block.prepareStatement(
                            "select user_id from v3_pocoma_user where user_id=? for update")) {
                s.setObject(1, a.userId().value());
                s.executeQuery().close();
            }
            var future =
                    threads.submit(
                            () ->
                                    run(
                                            w,
                                            stage -> {
                                                if (stage.equals("after_lock")) {
                                                    pid.set(
                                                            jdbc.queryForObject(
                                                                    "select pg_backend_pid()",
                                                                    Integer.class));
                                                    entered.countDown();
                                                }
                                            }));
            await(entered);
            blocked(pid.get(), blocker);
            assertEquals(
                    0,
                    admin.queryForObject(
                            "select count(*) from pg_locks where pid=? and"
                                    + " relation='v3_event_counter'::regclass",
                            Integer.class,
                            pid.get()));
            evidence.add("FK wait before apply returns; no sequence-counter relation opened");
            block.rollback();
            changed(future.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void l3_deferred_commit_has_no_new_business_locks() throws Exception {
        var e = key("initiator");
        var a = changed(run(admit(registration(e)))).identity();
        var target = key("target");
        var first = changed(run(admit(link(e, a.bindingToken(), target)))).identity();
        run(admit(unlink(e, a.bindingToken(), target)));
        var w = admit(link(e, a.bindingToken(), target));
        var beforeClose = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var positioned = new CountDownLatch(1);
        var releaseCommit = new CountDownLatch(1);
        var pid = new AtomicInteger();
        var future =
                threads.submit(
                        () ->
                                run(
                                        w,
                                        stage -> {
                                            if (stage.equals("before_close")) {
                                                pid.set(
                                                        jdbc.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                beforeClose.countDown();
                                                await(releaseClose);
                                            }
                                            if (stage.equals("before_commit")) {
                                                positioned.countDown();
                                                await(releaseCommit);
                                            }
                                        }));
        await(beforeClose);
        var relations = relations(pid.get());
        // Probe a historical predecessor read by terminal checks, but not FK-protected.
        try (var probe = source.getConnection()) {
            probe.setAutoCommit(false);
            try (var s =
                    probe.prepareStatement(
                            "select version from v3_eui_version where eui_id=? and version=2 for"
                                    + " update nowait")) {
                s.setObject(1, first.id().value());
                s.executeQuery().close();
            }
            // The current target anchor IS protected through commit.
            try (var anchorProbe = source.getConnection()) {
                anchorProbe.setAutoCommit(false);
                try (var s =
                        anchorProbe.prepareStatement(
                                "select eui_id from v3_eui_authority where eui_id=? for update"
                                        + " nowait")) {
                    s.setObject(1, first.id().value());
                    assertEquals(
                            "55P03",
                            assertThrows(SQLException.class, s::executeQuery).getSQLState());
                } finally {
                    anchorProbe.rollback();
                }
            }
            releaseClose.countDown();
            await(positioned);
            var added = new HashSet<>(relations(pid.get()));
            added.removeAll(relations);
            assertTrue(
                    added.stream()
                            .noneMatch(
                                    name ->
                                            name.startsWith("v3_identity_")
                                                    || name.startsWith("v3_eui_")
                                                    || name.startsWith("v3_pocoma_")),
                    "All business relations opened before closure");
            evidence.add("prefix relations=" + relations + " suffix added=" + added);
            releaseCommit.countDown();
            changed(future.get(10, TimeUnit.SECONDS));
            // Commit completed while the independent predecessor FOR UPDATE remains held.
            evidence.add(
                    "physical commit succeeded with predecessor row exclusively held by independent"
                            + " PID="
                            + probe.unwrap(org.postgresql.PGConnection.class).getBackendPID());
            probe.rollback();
        } finally {
            releaseClose.countDown();
            releaseCommit.countDown();
        }
        assertCatalog();
    }

    @Test
    void l3_bad_server_encoding_preflight() {
        String database = schema + "_encoding";
        admin.execute(
                "create database "
                        + database
                        + " template template0 encoding 'SQL_ASCII' lc_collate 'C' lc_ctype 'C'");
        var url = PG.getJdbcUrl().replace("/" + PG.getDatabaseName() + "?", "/" + database + "?");
        var wrong =
                new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                url, PG.getUsername(), PG.getPassword()));
        assertEquals("SQL_ASCII", wrong.queryForObject("show server_encoding", String.class));
        assertThrows(AssertionError.class, () -> assertEncodingAndCollation(wrong));
        evidence.add(
                "I2 harness preflight rejects real SQL_ASCII database before any EUI access; no"
                        + " runtime activation");
    }

    @Test
    void l3_other_family_suffix_is_untouched() {
        probeOtherFamilies();
        // Reproduce the original I2 defect in a disposable negative schema: no bypass or PASS
        // filter.
        admin.execute("drop trigger v3_identity_position_unit on v3_event_position");
        admin.execute(
                "create constraint trigger v3_identity_position_unit after insert on"
                        + " v3_event_position deferrable initially deferred for each row execute"
                        + " function v3_identity_require_position()");
        assertThrows(AssertionError.class, this::assertCatalog);
        assertThrows(AssertionError.class, this::probeOtherFamilies);
        admin.execute("drop trigger v3_identity_position_unit on v3_event_position");
        admin.execute(
                "create constraint trigger v3_identity_position_unit after insert on"
                        + " v3_event_position deferrable initially deferred for each row when"
                        + " (new.family in ('IDENTITY','IDENTITY_COMPLETION')) execute function"
                        + " v3_identity_require_position()");
        assertCatalog();
    }

    void probeOtherFamilies() {
        tx.write(
                () -> {
                    int pid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                    // Technical sequence probe only: no claim of a real Pot/Command participant
                    // here.
                    com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions
                            .closePublication(source);
                    position("POT", List.of(UUID.randomUUID()));
                    position("COMMAND_COMPLETION", List.of(UUID.randomUUID()));
                    // Execute precisely the deferred new trigger as commit would, retaining locks
                    // for the probe.
                    jdbc.execute("set constraints v3_identity_position_unit immediate");
                    var opened = relations(pid);
                    evidence.add("other-family suffix relations=" + opened);
                    assertTrue(
                            opened.stream()
                                    .noneMatch(
                                            n ->
                                                    n.startsWith("v3_identity_")
                                                            || n.startsWith("v3_eui_")
                                                            || n.startsWith("v3_pocoma_")),
                            "Other-family suffix must not open Identity business relations");
                    return null;
                });
    }

    Set<String> relations(int pid) {
        return new HashSet<>(
                admin.queryForList(
                        "select distinct c.relname from pg_locks l join pg_class c on"
                                + " c.oid=l.relation where l.pid=? and"
                                + " c.relnamespace=current_schema()::regnamespace and l.granted",
                        String.class,
                        pid));
    }

    void assertEncodingAndCollation() {
        assertEncodingAndCollation(jdbc);
    }

    void assertEncodingAndCollation(org.springframework.jdbc.core.JdbcTemplate target) {
        assertEquals("UTF8", target.queryForObject("show server_encoding", String.class));
        assertEquals("UTF8", target.queryForObject("show client_encoding", String.class));
        var collations =
                target.queryForList(
                        "select col.collname from pg_attribute a join pg_collation col on"
                            + " col.oid=a.attcollation where a.attrelid in"
                            + " ('v3_eui_authority'::regclass,'v3_identity_request'::regclass) and"
                            + " a.attname in"
                            + " ('issuer','subject','requester_issuer','requester_subject','target_issuer','target_subject')",
                        String.class);
        assertEquals(6, collations.size());
        assertTrue(collations.stream().allMatch("C"::equals));
    }

    static Set<String> terminalFunctions() {
        return Set.of(
                "v3_identity_check_unit",
                "v3_identity_require_unit",
                "v3_identity_require_user_unit",
                "v3_identity_require_completion_unit",
                "v3_identity_require_position",
                "v3_identity_require_counter");
    }

    void assertCatalog() {
        String positionTrigger =
                admin.queryForObject(
                        "select pg_get_triggerdef(oid) from pg_trigger where"
                                + " tgrelid='v3_event_position'::regclass and"
                                + " tgname='v3_identity_position_unit'",
                        String.class);
        assertTrue(
                positionTrigger.contains("WHEN") && positionTrigger.contains("IDENTITY_COMPLETION"),
                "Identity position trigger must be family-scoped");
        var fks =
                admin.queryForList(
                        "select"
                            + " conname,condeferrable,confdeltype,confupdtype,conrelid::regclass::text"
                            + " source_table,confrelid::regclass::text target_table from"
                            + " pg_constraint where connamespace=current_schema()::regnamespace and"
                            + " contype='f' and conrelid in (select oid from pg_class where"
                            + " relnamespace=current_schema()::regnamespace and (relname like"
                            + " 'v3_identity_%' or relname in"
                            + " ('v3_pocoma_user','v3_eui_version','v3_eui_authority')))");
        var expectedTargets =
                Map.of(
                        "v3_identity_user_request_fk",
                        "v3_identity_request",
                        "v3_identity_eui_anchor_fk",
                        "v3_eui_authority",
                        "v3_identity_eui_user_fk",
                        "v3_pocoma_user",
                        "v3_identity_eui_request_fk",
                        "v3_identity_request",
                        "v3_identity_result_request_fk",
                        "v3_identity_request",
                        "v3_identity_result_target_fk",
                        "v3_eui_version",
                        "v3_identity_event_request_fk",
                        "v3_identity_request",
                        "v3_identity_completion_result_fk",
                        "v3_identity_request_result");
        assertEquals(
                expectedTargets,
                fks.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        r -> (String) r.get("conname"),
                                        r -> (String) r.get("target_table"))));
        assertEquals(8, fks.size());
        assertTrue(
                fks.stream()
                        .allMatch(
                                r ->
                                        Boolean.FALSE.equals(r.get("condeferrable"))
                                                && "a".equals(r.get("confdeltype").toString())
                                                && "a".equals(r.get("confupdtype").toString())));
        var names =
                Set.of(
                        "v3_identity_anchor_state",
                        "v3_identity_version_state",
                        "v3_identity_request_unit",
                        "v3_identity_result_unit",
                        "v3_identity_user_unit",
                        "v3_identity_version_unit",
                        "v3_identity_event_unit",
                        "v3_identity_completion_unit",
                        "v3_identity_position_unit",
                        "v3_identity_counter_unit");
        var triggers =
                admin.queryForList(
                        "select t.tgname,t.tgdeferrable,t.tginitdeferred,p.proname from pg_trigger"
                            + " t join pg_class c on c.oid=t.tgrelid join pg_proc p on"
                            + " p.oid=t.tgfoid where c.relnamespace=current_schema()::regnamespace"
                            + " and not t.tgisinternal and t.tgconstraint<>0 and (c.relname like"
                            + " 'v3_identity_%' or c.relname in"
                            + " ('v3_pocoma_user','v3_eui_version','v3_eui_authority','v3_event_position','v3_event_counter'))");
        assertEquals(
                names,
                triggers.stream()
                        .map(r -> (String) r.get("tgname"))
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(
                triggers.stream()
                        .allMatch(
                                r ->
                                        Boolean.TRUE.equals(r.get("tgdeferrable"))
                                                && Boolean.TRUE.equals(r.get("tginitdeferred"))));
        for (String f : terminalFunctions()) {
            String body =
                    admin.queryForObject(
                                    "select prosrc from pg_proc where"
                                            + " pronamespace=current_schema()::regnamespace and"
                                            + " proname=?",
                                    String.class,
                                    f)
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("--[^\\n]*", "");
            assertFalse(
                    java.util.regex.Pattern.compile(
                                    "\\b(insert|update|delete|merge|execute|lock|nextval|setval|pg_advisory\\w*)\\b|for\\s+(share|key|no)")
                            .matcher(body)
                            .find(),
                    f);
            var calls = java.util.regex.Pattern.compile("\\b(v3_[a-z_]+)\\s*\\(").matcher(body);
            while (calls.find())
                assertEquals(
                        "v3_identity_check_unit", calls.group(1), "Closed terminal call graph");
        }
    }

    record Staged(UUID userEvent, UUID euiEvent, UUID completion) {}

    Staged stageRegistration(Work w, String corruption) {
        jdbc.queryForList(
                "select 1 from consumption_slot where consumption_id=? and consumable_id=? for"
                        + " update",
                CONSUMPTION,
                w.id());
        var ids = authority.lockInCanonicalOrder(List.of(w.requester()));
        var result =
                changed(
                        IdentityTransitions.registration(
                                w.requester(),
                                authority.current(w.requester()),
                                ids.get(w.requester()),
                                new PocomaUserId(UUID.randomUUID()),
                                w.fresh()));
        authority.apply(result, w.id());
        jdbc.update(
                "insert into v3_identity_request_result values (?,?,?,?,?)",
                w.id(),
                corruption.equals("rejected-effect") ? "REJECTED" : "CHANGED",
                corruption.equals("rejected-effect") ? null : result.identity().id().value(),
                corruption.equals("rejected-effect") ? null : 1L,
                corruption.equals("rejected-effect") ? "INVALID_BINDING" : null);
        jdbc.update("update v3_identity_request set status='TERMINAL' where request_id=?", w.id());
        if (!corruption.equals("slot-left"))
            jdbc.update(
                    "delete from consumption_slot where consumption_id=? and consumable_id=?",
                    CONSUMPTION,
                    w.id());
        UUID user =
                event(w.id(), "POCOMA_USER", result.createdUser().orElseThrow().id().value(), 1);
        UUID eui =
                event(
                        w.id(),
                        "EXTERNAL_USER_IDENTITY",
                        corruption.equals("wrong-subject")
                                ? UUID.randomUUID()
                                : result.identity().id().value(),
                        1);
        if (corruption.equals("extra-event"))
            event(w.id(), "EXTERNAL_USER_IDENTITY", UUID.randomUUID(), 2);
        if (corruption.equals("request-target")) {
            var other =
                    admit(registration(key("other"))); // Wrong provenance cannot replace the exact
            // result/state pairing.
            jdbc.update(
                    "insert into v3_identity_request_result values (?,'CHANGED',?,1,null)",
                    other.id(),
                    result.identity().id().value());
        }
        UUID completion = UUID.randomUUID();
        jdbc.update(
                "insert into v3_identity_completion_event values (?,?,?,clock_timestamp())",
                completion,
                w.operation(),
                w.id());
        jdbc.execute(STRUCTURAL);
        return new Staged(user, eui, completion);
    }

    void sqlFail(String constraint, String... statements) throws Exception {
        var before = vector();
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            SQLException failure =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                for (String sql : statements) s.execute(sql);
                                c.commit();
                            });
            c.rollback();
            assertTrue(failure.getSQLState().startsWith("23"));
            assertInstanceOf(PSQLException.class, failure);
            if (constraint.equals("v3_position_immutable")) {
                assertNull(((PSQLException) failure).getServerErrorMessage().getConstraint());
                assertEquals(
                        "Immutable Command result/publication",
                        ((PSQLException) failure).getServerErrorMessage().getMessage());
                assertEquals(
                        1,
                        admin.queryForObject(
                                "select count(*) from pg_trigger where"
                                        + " tgrelid='v3_event_position'::regclass and"
                                        + " tgname='v3_position_immutable'",
                                Integer.class));
            } else
                assertEquals(
                        constraint,
                        ((PSQLException) failure).getServerErrorMessage().getConstraint());
            evidence.add(
                    "runtime rejected constraint="
                            + constraint
                            + " sqlstate="
                            + failure.getSQLState());
        }
        assertEquals(before, vector());
    }
}
