package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.consumption;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

@Testcontainers
class ReadConsumptionFoundationPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    static final String C = "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a";
    DriverManagerDataSource source;
    JdbcTemplate sql;
    String schema;

    @BeforeAll
    static void initialize() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
                var s = c.createStatement()) {
            s.execute(
                    "create extension pgcrypto with schema public;create role l2_read_runtime login"
                        + " password 'fixture-runtime'");
        }
    }

    @BeforeEach
    void migrateAndSeed() throws Exception {
        schema = "l2r_" + UUID.randomUUID().toString().replace("-", "");
        source =
                new DriverManagerDataSource(
                        PG.getJdbcUrl() + "&currentSchema=" + schema,
                        PG.getUsername(),
                        PG.getPassword());
        sql = new JdbcTemplate(source);
        Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/v3-consumption-read/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                "l2_read_runtime"))
                .load()
                .migrate();
        String seed =
                Files.readString(Path.of("../../scripts/v3/bootstrap-l2-routing.sql"))
                        .replace(":'authority'", "'READ'")
                        .replace(":'expected_database'", "'" + PG.getDatabaseName() + "'")
                        .replace(":'expected_schema'", "'" + schema + "'")
                        .replace(":'expected_admin'", "'" + PG.getUsername() + "'");
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            s.execute(seed);
        }
    }

    void refused(String query, String state) throws Exception {
        try (var c = source.getConnection();
                var s = c.createStatement()) {
            c.setAutoCommit(false);
            var e =
                    assertThrows(
                            SQLException.class,
                            () -> {
                                s.execute(query);
                                c.commit();
                            });
            assertEquals(state, e.getSQLState(), query);
            c.rollback();
        }
    }

    @Test
    void readHasExactlyFourTechnicalTablesSevenRoutesAndTwentyEightPartitions() throws Exception {
        assertEquals(
                4,
                sql.queryForObject(
                        "select count(*) from pg_tables where schemaname=current_schema() and"
                            + " tablename<>'flyway_schema_history'",
                        Integer.class));
        assertEquals(
                7,
                sql.queryForObject(
                        "select count(*) from consumption_routing_contract", Integer.class));
        assertEquals(
                28, sql.queryForObject("select count(*) from partition_ownership", Integer.class));
        assertNull(sql.queryForObject("select to_regclass('v3_command')::text", String.class));
        var dir = Path.of("target/l2-i1-proof");
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("read-catalog.txt"),
                sql.queryForList(
                                "select * from consumption_routing_contract order by"
                                    + " consumption_id")
                        + "\n"
                        + sql.queryForList(
                                "select conname,pg_get_constraintdef(oid) from pg_constraint where"
                                    + " connamespace=current_schema()::regnamespace order by"
                                    + " conname")
                        + "\n"
                        + sql.queryForList(
                                "select tgname,pg_get_triggerdef(t.oid) from pg_trigger t join"
                                    + " pg_class c on c.oid=t.tgrelid where"
                                    + " c.relnamespace=current_schema()::regnamespace and not"
                                    + " t.tgisinternal")
                        + "\n"
                        + sql.queryForList(
                                "select indexdef from pg_indexes where schemaname=current_schema()")
                        + "\n");
    }

    @Test
    void checkpointIsMonotoneAndImmutableInIdentityAndNonnegative() throws Exception {
        sql.execute("insert into discovery_checkpoint values ('" + C + "',0,7)");
        for (String values :
                List.of(
                        "position=6",
                        "position=-1",
                        "partition_id=1",
                        "consumption_id='190a93c6-b8af-5ac7-8001-72e2ec53c2df'"))
            refused("update discovery_checkpoint set " + values, "23514");
        refused("insert into discovery_checkpoint values ('" + C + "',0,9)", "23505");
        sql.execute("update discovery_checkpoint set position=8");
        assertEquals(
                8, sql.queryForObject("select position from discovery_checkpoint", Integer.class));
        assertEquals(
                1, sql.queryForObject("select count(*) from discovery_checkpoint", Integer.class));
    }

    @Test
    void ownershipIdentityAndPairedLeaseAndSlotQuarantineAreEnforced() throws Exception {
        refused(
                "update partition_ownership set"
                    + " consumption_id='190a93c6-b8af-5ac7-8001-72e2ec53c2df' where"
                    + " consumption_id='"
                        + C
                        + "'",
                "23514");
        refused(
                "update partition_ownership set partition_id=1 where consumption_id='"
                        + C
                        + "' and partition_id=0",
                "23514");
        refused("update partition_ownership set owned_until=clock_timestamp()", "23514");
        sql.execute(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select '"
                        + C
                        + "','00000000-0000-0000-0000-000000000000',h,((h::bigint%4+4)%4)::integer"
                        + " from (select"
                        + " consumption_hash_v1('POT_EVENT','00000000-0000-0000-0000-000000000000')"
                        + " h) q");
        refused(
                "update consumption_slot set consumption_id='190a93c6-b8af-5ac7-8001-72e2ec53c2df'",
                "23514");
        refused("update consumption_slot set claimed_until=clock_timestamp()", "23514");
        refused(
                "update consumption_slot set state='QUARANTINED',claim_token='"
                        + C
                        + "',claimed_until=clock_timestamp()",
                "23514");
        sql.execute("update consumption_slot set state='QUARANTINED'");
        assertEquals(
                "QUARANTINED",
                sql.queryForObject("select state from consumption_slot", String.class));
        assertEquals(
                -830870973,
                sql.queryForObject("select partition_hash from consumption_slot", Integer.class));
    }

    @Test
    void actualReadRuntimeHasOnlySelectIncludingPublicAndMembership() throws Exception {
        try (var c =
                        DriverManager.getConnection(
                                PG.getJdbcUrl() + "&currentSchema=" + schema,
                                "l2_read_runtime",
                                "fixture-runtime");
                var s = c.createStatement()) {
            for (String t :
                    List.of(
                            "consumption_slot",
                            "consumption_routing_contract",
                            "partition_ownership",
                            "discovery_checkpoint")) {
                try (var r =
                        s.executeQuery(
                                "select has_table_privilege('"
                                        + t
                                        + "','SELECT'),has_table_privilege('"
                                        + t
                                        + "','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')")) {
                    assertTrue(r.next());
                    assertTrue(r.getBoolean(1));
                    assertFalse(r.getBoolean(2));
                }
                assertEquals(
                        "42501",
                        assertThrows(SQLException.class, () -> s.execute("delete from " + t))
                                .getSQLState());
            }
            assertEquals(
                    "42501",
                    assertThrows(
                                    SQLException.class,
                                    () -> s.execute("create table forbidden(x int)"))
                            .getSQLState());
        }
    }
}
