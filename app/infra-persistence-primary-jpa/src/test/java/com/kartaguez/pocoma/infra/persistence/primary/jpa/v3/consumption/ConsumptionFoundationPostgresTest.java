package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Real PostgreSQL schema/administration proof only; never calls an I2 engine. */
@Testcontainers
class ConsumptionFoundationPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    static final String C = "98f7268d-d340-581c-b83d-010926223e14";
    static final String I = "2999e75c-272d-5afe-a966-c9364ef021fe";
    static final String R = "00000000-0000-0000-0000-000000000001";
    static final String P = "00000000-0000-0000-0000-000000000002";
    static final String E = "00000000-0000-0000-0000-000000000003";
    String schema;
    DriverManagerDataSource source;
    JdbcTemplate sql;
    Map<String, String> functions, triggers, constraints;

    @BeforeAll
    static void roles() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var s = c.createStatement()) {
            s.execute("create extension pgcrypto with schema public");
            s.execute("create role l2_runtime login password 'fixture-runtime'");
        }
    }

    @BeforeEach
    void historicalFreshSchema() {
        schema = "l2_" + UUID.randomUUID().toString().replace("-", "");
        source =
                new DriverManagerDataSource(
                        PG.getJdbcUrl() + "&currentSchema=" + schema,
                        PG.getUsername(),
                        PG.getPassword());
        sql = new JdbcTemplate(source);
        migrations(false, Map.of()).migrate();
        functions =
                catalog(
                        "select p.proname,pg_get_functiondef(p.oid) from pg_proc p join"
                            + " pg_namespace n on n.oid=p.pronamespace where"
                            + " n.nspname=current_schema()");
        triggers =
                catalog(
                        "select tgname,pg_get_triggerdef(t.oid) from pg_trigger t join pg_class c"
                            + " on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace"
                            + " where n.nspname=current_schema() and not t.tgisinternal");
        constraints =
                catalog(
                        "select c.relname||'.'||conname,pg_get_constraintdef(t.oid) from"
                            + " pg_constraint t join pg_class c on c.oid=t.conrelid join"
                            + " pg_namespace n on n.oid=c.relnamespace where"
                            + " n.nspname=current_schema() and c.relname<>'v3_command_slot'");
    }

    Flyway migrations(boolean l2, Map<String, String> overrides) {
        var values =
                new HashMap<>(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                "l2_runtime"));
        values.putAll(overrides);
        return Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        l2
                                ? new String[] {
                                    "classpath:db/v3-pot/migration",
                                    "classpath:db/v3-pot-execution/migration",
                                    "classpath:db/v3-consumption-write/migration"
                                }
                                : new String[] {
                                    "classpath:db/v3-pot/migration",
                                    "classpath:db/v3-pot-execution/migration"
                                })
                .placeholders(values)
                .load();
    }

    Map<String, String> catalog(String query) {
        return sql.query(
                query,
                rs -> {
                    var m = new TreeMap<String, String>();
                    while (rs.next()) m.put(rs.getString(1), rs.getString(2));
                    return m;
                });
    }

    String seed() throws Exception {
        return Files.readString(Path.of("../../scripts/v3/bootstrap-l2-routing.sql"))
                .replace(":'authority'", "'WRITE'")
                .replace(":'expected_database'", "'" + PG.getDatabaseName() + "'")
                .replace(":'expected_schema'", "'" + schema + "'")
                .replace(":'expected_admin'", "'" + PG.getUsername() + "'");
    }

    void executeScript(String script) throws Exception {
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute(script);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    void installAndSeed() throws Exception {
        migrations(true, Map.of()).migrate();
        executeScript(seed());
    }

    int count(String table) {
        return sql.queryForObject("select count(*) from " + table, Integer.class);
    }

    void refused(String query, String state) throws Exception {
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            var error =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                s.execute(query);
                                c.commit();
                            },
                            query);
            assertEquals(state, error.getSQLState(), query);
            c.rollback();
        }
    }

    String command() {
        return "insert into"
                   + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                   + " values ('"
                + R
                + "','issuer','subject','token','"
                + P
                + "',0,'fixture','{}');";
    }

    String slot(String consumption) {
        return "insert into"
                   + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                   + " select '"
                + consumption
                + "','"
                + R
                + "',h,((h::bigint%4+4)%4)::integer from (select consumption_hash_v1(family,'"
                + R
                + "') h from consumption_routing_contract where consumption_id='"
                + consumption
                + "') q;";
    }

    String terminal(String outcome, boolean completion, boolean position) {
        String r = outcome.equals("REJECTED") ? "null,'fixture-rejection'" : "1,null";
        return "update v3_command set status='TERMINAL' where request_id='"
                + R
                + "';"
                + "insert into v3_command_result values ('"
                + R
                + "','"
                + outcome
                + "',"
                + r
                + ");"
                + (completion
                        ? "insert into v3_command_completion_event(event_id,subject_id,recorded_at)"
                              + " values ('"
                                + E
                                + "','"
                                + R
                                + "',clock_timestamp());"
                        : "")
                + (position
                        ? "insert into v3_event_position values ('COMMAND_COMPLETION',1,'"
                                + E
                                + "');update v3_event_counter set position=1 where"
                                + " family='COMMAND_COMPLETION';"
                        : "");
    }

    String pot() {
        return "insert into v3_pot_identity(pot_id) values ('"
                + P
                + "');"
                + "insert into v3_pot_version(pot_id,version,recorded_at) values ('"
                + P
                + "',1,clock_timestamp());insert into"
                + " v3_pot_header(pot_id,object_id,local_version,active_from,deleted) values ('"
                + P
                + "','"
                + P
                + "',1,1,false);insert into"
                + " v3_pot_details(pot_id,object_id,local_version,active_from,label,creator_id)"
                + " values ('"
                + P
                + "','"
                + P
                + "',1,1,'fixture','00000000-0000-0000-0000-000000000005');";
    }

    String event(boolean component, boolean position) {
        String event = "00000000-0000-0000-0000-000000000004";
        return "insert into v3_pot_event(event_id,pot_id,subject_version,request_id,recorded_at)"
                   + " values ('"
                + event
                + "','"
                + P
                + "',1,'"
                + R
                + "',clock_timestamp());"
                + (component
                        ? "insert into v3_pot_event_component values ('"
                                + event
                                + "','POT_HEADER','"
                                + P
                                + "',1);"
                        : "")
                + (position
                        ? "insert into v3_event_position values ('POT',1,'"
                                + event
                                + "');update v3_event_counter set position=1 where family='POT';"
                        : "");
    }

    void proof(String name, Object data) throws Exception {
        var dir = Path.of("target/l2-i1-proof");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".txt"), data + "\n");
    }

    @Test
    void preservesEveryL1FunctionTriggerAndConstraintExceptExactSlotPredicate() throws Exception {
        installAndSeed();
        var now =
                catalog(
                        "select p.proname,pg_get_functiondef(p.oid) from pg_proc p join"
                            + " pg_namespace n on n.oid=p.pronamespace where"
                            + " n.nspname=current_schema()");
        for (var e : functions.entrySet()) {
            String expected =
                    e.getValue()
                            .replace(
                                    "exists(select 1 from v3_command_slot where"
                                        + " request_id=c.request_id)",
                                    "exists(select 1 from consumption_slot s where"
                                        + " s.consumption_id='"
                                            + C
                                            + "'::uuid and s.consumable_id=c.request_id)");
            assertEquals(expected, now.get(e.getKey()), e.getKey());
        }
        var after =
                catalog(
                        "select tgname,pg_get_triggerdef(t.oid) from pg_trigger t join pg_class c"
                            + " on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace"
                            + " where n.nspname=current_schema() and not t.tgisinternal");
        triggers.forEach((k, v) -> assertEquals(v, after.get(k), k));
        var afterConstraints =
                catalog(
                        "select c.relname||'.'||conname,pg_get_constraintdef(t.oid) from"
                            + " pg_constraint t join pg_class c on c.oid=t.conrelid join"
                            + " pg_namespace n on n.oid=c.relnamespace where"
                            + " n.nspname=current_schema() and c.relname<>'v3_command_slot'");
        constraints.forEach((k, v) -> assertEquals(v, afterConstraints.get(k), k));
        assertNull(sql.queryForObject("select to_regclass('v3_command_slot')::text", String.class));
        assertEquals(2, count("consumption_routing_contract"));
        assertEquals(8, count("partition_ownership"));
        proof("l1-functions-before", functions);
        proof("l1-functions-after", now);
        proof("l1-triggers-before", triggers);
        proof("l1-triggers-after", after);
        proof("constraints", afterConstraints);
        proof(
                "indexes",
                sql.queryForList(
                        "select tablename,indexdef from pg_indexes where"
                            + " schemaname=current_schema() order by tablename,indexname"));
    }

    @Test
    void sqlMatchesAllTwentyOneIndependentDigestsHashesAndPartitions() throws Exception {
        installAndSeed();
        var lines =
                Files.readAllLines(
                        Path.of("../../docs/testing/evidence/L2-I1/routing-vectors.csv"));
        for (String line : lines.subList(1, lines.size())) {
            var v = line.split(",");
            assertEquals(
                    Integer.parseInt(v[3]),
                    sql.queryForObject(
                            "select consumption_hash_v1(?,?::uuid)", Integer.class, v[0], v[1]));
            assertEquals(
                    v[2],
                    sql.queryForObject(
                            "select"
                                + " encode(public.digest(convert_to('pocoma-consumable-v1','UTF8')||decode('00','hex')||convert_to(?,'UTF8')||decode('00','hex')||uuid_send(?::uuid),'sha256'),'hex')",
                            String.class,
                            v[0],
                            v[1]));
            assertEquals(
                    Integer.parseInt(v[4]),
                    sql.queryForObject(
                            "select ((consumption_hash_v1(?,?::uuid)::bigint%4+4)%4)::integer",
                            Integer.class, v[0], v[1]));
            assertEquals(
                    Integer.parseInt(v[5]),
                    sql.queryForObject(
                            "select ((consumption_hash_v1(?,?::uuid)::bigint%16+16)%16)::integer",
                            Integer.class, v[0], v[1]));
        }
        proof(
                "postgres",
                sql.queryForList(
                        "select"
                            + " version(),current_database(),current_schema(),current_user,inet_server_addr(),inet_server_port(),current_setting('transaction_isolation'),current_setting('server_encoding')"));
    }

    @Test
    void rejectsMalformedRowsAndPreservesDurableStateAfterEveryRollback() throws Exception {
        installAndSeed();
        for (String bad :
                List.of(
                        "insert into"
                            + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                            + " values ('"
                                + C
                                + "','"
                                + R
                                + "',0,0)",
                        slot(C).replace(
                                        "((h::bigint%4+4)%4)::integer",
                                        "(((h::bigint%4+4)%4+1)%4)::integer"),
                        "insert into partition_ownership values ('" + C + "',4,null,null)",
                        "update partition_ownership set owner_token='"
                                + R
                                + "' where consumption_id='"
                                + C
                                + "' and partition_id=0",
                        "insert into discovery_checkpoint values ('" + C + "',0,0)",
                        "insert into discovery_checkpoint values ('" + I + "',0,0)",
                        "update partition_ownership set partition_id=4 where consumption_id='"
                                + C
                                + "' and partition_id=0",
                        "update consumption_routing_contract set routing_version='sha256-v1'",
                        "delete from consumption_routing_contract")) refused(bad, "23514");
        refused(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " values ('"
                        + R
                        + "','"
                        + R
                        + "',0,0)",
                "23503");
        executeScript(slot(C));
        for (String change :
                List.of(
                        "consumable_id='" + P + "'",
                        "claim_token='" + R + "'",
                        "attempt_count=-1",
                        "state='DONE'",
                        "state='QUARANTINED',retry_after=clock_timestamp()",
                        "partition_hash=0"))
            refused("update consumption_slot set " + change, "23514");
        refused(slot(C), "23505");
        assertEquals(1, count("consumption_slot"));
        assertEquals(0, count("discovery_checkpoint"));
        proof("durable-invalid-rows", sql.queryForList("select * from consumption_slot"));
    }

    @Test
    void bootstrapRollsBackEachBoundaryRejectsWrongTargetRerunAndPartialSeed() throws Exception {
        migrations(true, Map.of()).migrate();
        String script = seed();
        for (String bad :
                List.of(
                        script.replace(
                                "bootstrap_authority', 'WRITE'", "bootstrap_authority', 'WRONG'"),
                        script.replace("'" + schema + "'", "'wrong_schema'"),
                        script.replace("'" + PG.getDatabaseName() + "'", "'wrong_database'"),
                        script.replace("'" + PG.getUsername() + "'", "'wrong_admin'"),
                        script.replace("with definitions", "select 1/0;\nwith definitions"),
                        script.replace(
                                "insert into partition_ownership",
                                "select 1/0;\ninsert into partition_ownership"),
                        script.replace("commit;", "select 1/0;\ncommit;"))) {
            assertThrows(SQLException.class, () -> executeScript(bad));
            assertEquals(0, count("consumption_routing_contract"));
            assertEquals(0, count("partition_ownership"));
        }
        sql.execute(
                "insert into consumption_routing_contract values ('"
                        + C
                        + "','COMMAND','sha256-v1',4)");
        assertThrows(SQLException.class, () -> executeScript(script));
        assertEquals(1, count("consumption_routing_contract"));
    }

    @Test
    void serializesTwoAdministratorsWithObservedPostgresBlocker() throws Exception {
        migrations(true, Map.of()).migrate();
        String script = seed();
        try (var first = source.getConnection();
                var second = source.getConnection();
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            int firstPid, secondPid;
            try (var s = first.createStatement();
                    var rs = s.executeQuery("select pg_backend_pid()")) {
                rs.next();
                firstPid = rs.getInt(1);
            }
            try (var s = second.createStatement();
                    var rs = s.executeQuery("select pg_backend_pid()")) {
                rs.next();
                secondPid = rs.getInt(1);
            }
            first.createStatement()
                    .execute("lock table consumption_routing_contract in share row exclusive mode");
            var waiting =
                    pool.submit(
                            () -> {
                                try {
                                    second.createStatement().execute(script);
                                    return "unexpected commit";
                                } catch (SQLException e) {
                                    second.rollback();
                                    return e.getSQLState();
                                }
                            });
            boolean blocked = false;
            var deadline = Instant.now().plusSeconds(10);
            while (Instant.now().isBefore(deadline)) {
                if (sql.queryForObject(
                        "select ?=any(pg_blocking_pids(?))", Boolean.class, firstPid, secondPid)) {
                    blocked = true;
                    break;
                }
                Thread.onSpinWait();
            }
            assertTrue(blocked, "real table lock wait required");
            proof(
                    "two-admin-blocker",
                    sql.queryForList(
                            "select pid,pg_blocking_pids(pid),wait_event_type,wait_event,query from"
                                + " pg_stat_activity where pid="
                                    + secondPid));
            first.createStatement().execute(script);
            first.commit();
            assertEquals("23514", waiting.get(10, TimeUnit.SECONDS));
            assertEquals(2, count("consumption_routing_contract"));
            assertEquals(8, count("partition_ownership"));
            assertThrows(SQLException.class, () -> executeScript(script));
            proof(
                    "two-admin-durable",
                    sql.queryForList(
                            "select * from consumption_routing_contract order by consumption_id"));
        }
    }

    @Test
    void pilotContractChecksRejectUnknownFamilyVersionAndPartitionCount() throws Exception {
        migrations(true, Map.of()).migrate();
        for (String row :
                List.of(
                        "'COMMAND','sha256-v1',16",
                        "'COMMAND','sha256-v2',4",
                        "'UNKNOWN','sha256-v1',4"))
            refused(
                    "insert into consumption_routing_contract values ('" + C + "'," + row + ")",
                    "23514");
        assertEquals(0, count("consumption_routing_contract"));
    }

    @Test
    void missingPublicPgcryptoFailsBeforeAnyFoundationDdl() throws Exception {
        String db = "no_digest_" + schema;
        sql.execute("create database " + db);
        source =
                new DriverManagerDataSource(
                        PG.getJdbcUrl().replace("/" + PG.getDatabaseName() + "?", "/" + db + "?")
                                + "&currentSchema="
                                + schema,
                        PG.getUsername(),
                        PG.getPassword());
        sql = new JdbcTemplate(source);
        assertThrows(
                Exception.class, () -> migrations(true, Map.of("expectedDatabase", db)).migrate());
        assertNull(
                sql.queryForObject("select to_regclass('consumption_slot')::text", String.class));
        assertNull(
                sql.queryForObject(
                        "select to_regprocedure('public.digest(bytea,text)')::text", String.class));
    }

    @Test
    void runtimeCannotWriteTruncateCreateAlterDropOrBootstrap() throws Exception {
        installAndSeed();
        var runtime =
                new DriverManagerDataSource(
                        PG.getJdbcUrl() + "&currentSchema=" + schema,
                        "l2_runtime",
                        "fixture-runtime");
        try (var c = runtime.getConnection();
                var s = c.createStatement()) {
            for (String table :
                    List.of(
                            "consumption_slot",
                            "partition_ownership",
                            "discovery_checkpoint",
                            "consumption_routing_contract")) {
                assertEquals(
                        0,
                        new JdbcTemplate(runtime)
                                .queryForObject(
                                        "select"
                                            + " has_table_privilege(current_user,?,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')::integer",
                                        Integer.class,
                                        table));
                for (String query :
                        List.of(
                                "delete from " + table,
                                "truncate " + table,
                                "alter table " + table + " add x int",
                                "drop table " + table,
                                "insert into " + table + " select * from " + table,
                                "update " + table + " set consumption_id=consumption_id"))
                    assertEquals(
                            "42501",
                            assertThrows(SQLException.class, () -> s.execute(query)).getSQLState(),
                            query);
            }
            assertEquals(
                    "42501",
                    assertThrows(
                                    SQLException.class,
                                    () -> s.execute("create table forbidden(x int)"))
                            .getSQLState());
            assertEquals(
                    "42501",
                    assertThrows(SQLException.class, () -> s.execute(seed())).getSQLState());
            s.execute("rollback");
        }
        assertEquals(2, count("consumption_routing_contract"));
        assertEquals(8, count("partition_ownership"));
        proof(
                "runtime-grants",
                sql.queryForList(
                        "select * from information_schema.role_table_grants where"
                            + " table_schema=current_schema() and grantee='l2_runtime'"));
    }

    @Test
    void wrongMigrationTargetAndHistoricalPendingAreNeverDestroyed() throws Exception {
        for (var mismatch :
                List.of(
                        Map.of("expectedDatabase", "wrong_db"),
                        Map.of("expectedSchema", "wrong_schema"),
                        Map.of("expectedAdmin", "wrong_admin"))) {
            assertThrows(Exception.class, () -> migrations(true, mismatch).migrate());
            assertNull(
                    sql.queryForObject(
                            "select to_regclass('consumption_slot')::text", String.class));
            assertNotNull(
                    sql.queryForObject(
                            "select to_regclass('v3_command_slot')::text", String.class));
        }
        sql.execute(command() + "insert into v3_command_slot(request_id) values ('" + R + "');");
        var before = sql.queryForList("select * from v3_command");
        assertThrows(Exception.class, () -> migrations(true, Map.of()).migrate());
        assertEquals(before, sql.queryForList("select * from v3_command"));
        assertEquals(1, count("v3_command_slot"));
        proof("historical-pending-preserved", before);
    }

    @Test
    void historicalTerminalAndOriginalPublicationsArePreservedOnRefusal() throws Exception {
        executeScript(command() + terminal("REJECTED", true, true));
        var before =
                sql.queryForList(
                        "select c.*,r.outcome from v3_command c join v3_command_result r"
                            + " using(request_id)");
        assertThrows(Exception.class, () -> migrations(true, Map.of()).migrate());
        assertEquals(
                before,
                sql.queryForList(
                        "select c.*,r.outcome from v3_command c join v3_command_result r"
                            + " using(request_id)"));
        assertEquals(1, count("v3_command_completion_event"));
        assertEquals(1, count("v3_event_position"));
        assertNotNull(
                sql.queryForObject("select to_regclass('v3_command_slot')::text", String.class));
        proof("historical-terminal-preserved", before);
    }

    @Test
    void refusesInheritedRuntimeWritePrivilegesAtomically() {
        sql.execute(
                "create role inherited_"
                        + schema
                        + ";grant inherited_"
                        + schema
                        + " to l2_runtime;");
        sql.execute(
                "alter default privileges in schema "
                        + schema
                        + " grant insert on tables to inherited_"
                        + schema);
        assertThrows(Exception.class, () -> migrations(true, Map.of()).migrate());
        assertNull(
                sql.queryForObject("select to_regclass('consumption_slot')::text", String.class));
        sql.execute("revoke inherited_" + schema + " from l2_runtime");
    }

    @Test
    void noinheritMembershipCannotBypassMigrationPrivilegeGuard() {
        String writer = "writer_" + schema, runtime = "runtime_" + schema;
        sql.execute(
                "create role "
                        + writer
                        + ";create role "
                        + runtime
                        + " noinherit;grant "
                        + writer
                        + " to "
                        + runtime);
        sql.execute(
                "alter default privileges in schema "
                        + schema
                        + " grant insert on tables to "
                        + writer);
        assertThrows(
                Exception.class,
                () -> migrations(true, Map.of("expectedRuntime", runtime)).migrate());
        assertNull(
                sql.queryForObject("select to_regclass('consumption_slot')::text", String.class));
    }

    @Test
    void pendingAndTerminalNegativeUnitsRollbackWithoutPartialPublications() throws Exception {
        installAndSeed();
        sql.execute(command());
        for (String bad :
                List.of(
                        "insert into v3_command_result values ('"
                                + R
                                + "','REJECTED',null,'fixture')",
                        "update v3_command set status='TERMINAL'",
                        terminal("REJECTED", false, false),
                        terminal("REJECTED", true, false),
                        slot(C) + terminal("REJECTED", true, true),
                        terminal("CHANGED", true, true),
                        pot() + terminal("CHANGED", true, true) + event(false, true),
                        pot() + terminal("CHANGED", true, true) + event(true, false),
                        pot() + terminal("UNCHANGED", true, true) + event(true, true),
                        pot() + terminal("REJECTED", true, true) + event(true, true))) {
            refused(bad, "23514");
            assertEquals(
                    "PENDING", sql.queryForObject("select status from v3_command", String.class));
            for (String table :
                    List.of(
                            "v3_command_result",
                            "v3_command_completion_event",
                            "v3_event_position",
                            "v3_pot_identity",
                            "v3_pot_event",
                            "consumption_slot")) assertEquals(0, count(table), table);
        }
        proof("command-negative-durable", sql.queryForList("select * from v3_command"));
    }

    @Test
    void acceptsCompleteChangedSqlUnitAndPreservesPublicationImmutability() throws Exception {
        installAndSeed();
        executeScript(command() + pot() + terminal("CHANGED", true, true) + event(true, true));
        assertEquals(1, count("v3_command_result"));
        assertEquals(1, count("v3_pot_event"));
        assertEquals(2, count("v3_event_position"));
        for (String table :
                List.of(
                        "v3_command_result",
                        "v3_pot_event",
                        "v3_pot_event_component",
                        "v3_command_completion_event",
                        "v3_event_position")) refused("delete from " + table, "23514");
        proof(
                "complete-changed-unit",
                sql.queryForList(
                        "select c.*,r.outcome,r.resulting_pot_version from v3_command c join"
                            + " v3_command_result r using(request_id)"));
    }

    @Test
    void otherConsumptionSlotDoesNotMasqueradeAsCanonicalCommandSlot() throws Exception {
        installAndSeed();
        executeScript(command() + slot(I) + terminal("REJECTED", true, true));
        assertEquals("TERMINAL", sql.queryForObject("select status from v3_command", String.class));
        assertEquals(
                I,
                sql.queryForObject(
                        "select consumption_id::text from consumption_slot", String.class));
        proof("canonical-predicate-scope", sql.queryForList("select * from consumption_slot"));
    }
}
