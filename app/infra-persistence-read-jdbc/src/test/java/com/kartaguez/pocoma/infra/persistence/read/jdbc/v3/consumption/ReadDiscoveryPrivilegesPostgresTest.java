package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.consumption;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;

import java.nio.file.*;
import java.util.*;

@Testcontainers
class ReadDiscoveryPrivilegesPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    String schema, worker, react;
    final List<String> trace = new ArrayList<>();

    long script(String name) throws Exception {
        PG.copyFileToContainer(
                MountableFile.forHostPath(Path.of("../../scripts/v3/" + name).toAbsolutePath()),
                "/tmp/" + name);
        var r =
                PG.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + schema,
                        "psql",
                        "-X",
                        "-U",
                        PG.getUsername(),
                        "-d",
                        PG.getDatabaseName(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "authority=READ",
                        "-v",
                        "expected_database=" + PG.getDatabaseName(),
                        "-v",
                        "expected_schema=" + schema,
                        "-v",
                        "expected_admin=" + PG.getUsername(),
                        "-v",
                        "runtime_role=" + worker,
                        "-v",
                        "reactivation_role=" + react,
                        "-f",
                        "/tmp/" + name);
        trace.add(name + " exit=" + r.getExitCode() + " " + r.getStdout() + r.getStderr());
        return r.getExitCode();
    }

    @Test
    void effectiveDiscoveryColumnPrivileges() throws Exception {
        schema = "i4r_" + UUID.randomUUID().toString().replace("-", "");
        worker = schema + "_w";
        react = schema + "_a";
        String url = PG.getJdbcUrl() + "&currentSchema=" + schema;
        var source = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        var admin = new JdbcTemplate(source);
        admin.execute(
                "create extension pgcrypto with schema public;create role "
                        + worker
                        + " login password 'fixture';create role "
                        + react
                        + " login password 'fixture'");
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
                                worker))
                .load()
                .migrate();
        assertEquals(0, script("bootstrap-l2-routing.sql"));
        assertEquals(0, script("grant-l2-lifecycle.sql"));
        var sql = new JdbcTemplate(new DriverManagerDataSource(url, worker, "fixture"));
        String insert =
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select '3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a',"
                    + " '00000000-0000-0000-0000-000000000002',consumption_hash_v1('POT_EVENT','00000000-0000-0000-0000-000000000002'),0";
        assertThrows(RuntimeException.class, () -> sql.update(insert));
        assertEquals(0, script("grant-l2-discovery.sql"));
        assertEquals(0, script("grant-l2-discovery.sql"));
        assertNotEquals(
                0, script("grant-l2-lifecycle.sql")); // Strict I3 never normalizes union rights.
        assertEquals(1, sql.update(insert));
        assertEquals(
                1,
                sql.update(
                        "insert into discovery_checkpoint(consumption_id,partition_id,position)"
                                + " values ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a',0,1)"));
        assertEquals(1, sql.update("update discovery_checkpoint set position=2"));
        for (String statement :
                List.of(
                        "update consumption_slot set partition_id=0",
                        "update consumption_slot set consumable_id=consumable_id",
                        "update consumption_routing_contract set partition_count=4",
                        "delete from discovery_checkpoint",
                        "truncate consumption_slot",
                        "create table forbidden(id int)",
                        "alter table consumption_slot owner to " + worker,
                        "set role " + react,
                        "insert into"
                            + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state)"
                            + " select consumption_id,gen_random_uuid(),0,0,'AVAILABLE' from"
                            + " consumption_slot",
                        "insert into"
                            + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,attempt_count)"
                            + " select consumption_id,gen_random_uuid(),0,0,0 from"
                            + " consumption_slot",
                        "insert into"
                            + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,claim_token)"
                            + " select consumption_id,gen_random_uuid(),0,0,null from"
                            + " consumption_slot"))
            assertThrows(RuntimeException.class, () -> sql.execute(statement), statement);
        for (String col :
                List.of("consumption_id", "consumable_id", "partition_hash", "partition_id"))
            assertTrue(
                    sql.queryForObject(
                            "select has_column_privilege('consumption_slot',?,'INSERT')",
                            Boolean.class,
                            col));
        assertFalse(
                sql.queryForObject(
                        "select has_table_privilege('consumption_slot','INSERT')", Boolean.class));
        for (String grants :
                List.of(
                        "grant insert(state) on consumption_slot to " + worker,
                        "grant insert on consumption_slot to " + worker,
                        "grant update(partition_id) on consumption_slot to public")) {
            admin.execute(grants);
            assertNotEquals(0, script("grant-l2-discovery.sql"));
            admin.execute(
                    "revoke insert on consumption_slot from "
                            + worker
                            + ";revoke insert(state) on consumption_slot from "
                            + worker
                            + ";revoke update(partition_id) on consumption_slot from public");
            assertEquals(0, script("grant-l2-discovery.sql"));
        }
        String member = schema + "_m";
        admin.execute("create role " + member);
        for (boolean inherit : List.of(true, false)) {
            admin.execute(
                    "alter role "
                            + worker
                            + (inherit ? " inherit" : " noinherit")
                            + ";grant "
                            + member
                            + " to "
                            + worker);
            assertNotEquals(0, script("grant-l2-discovery.sql"));
            admin.execute("revoke " + member + " from " + worker);
        }
        assertEquals(0, script("grant-l2-discovery.sql"));
        assertEquals(1, sql.queryForObject("select count(*) from consumption_slot", Integer.class));
        assertEquals(
                2, sql.queryForObject("select position from discovery_checkpoint", Integer.class));
        trace.add(
                sql.queryForMap(
                                "select"
                                    + " session_user,current_user,clock_timestamp(),pg_backend_pid(),pg_current_xact_id()::text,version()")
                        .toString());
        trace.add(
                admin.queryForList(
                                "select table_name,column_name,grantee,privilege_type from"
                                        + " information_schema.column_privileges where"
                                        + " table_schema=current_schema() order by 1,2,3,4")
                        .toString());
        var path = Path.of("target/v3-i4-evidence/read-effective-privileges.txt");
        Files.createDirectories(path.getParent());
        Files.write(path, trace);
    }
}
