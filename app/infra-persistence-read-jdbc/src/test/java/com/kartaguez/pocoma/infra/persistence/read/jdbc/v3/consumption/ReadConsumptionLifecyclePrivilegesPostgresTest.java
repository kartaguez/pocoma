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
class ReadConsumptionLifecyclePrivilegesPostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    @Test
    void effectiveLeastPrivileges() throws Exception {
        String schema = "i3r_" + UUID.randomUUID().toString().replace("-", "");
        String worker = schema + "_w", react = schema + "_a";
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
        var trace = new ArrayList<String>();
        for (String script : List.of("bootstrap-l2-routing.sql", "grant-l2-lifecycle.sql")) {
            PG.copyFileToContainer(
                    MountableFile.forHostPath(
                            Path.of("../../scripts/v3/" + script).toAbsolutePath()),
                    "/tmp/" + script);
            var result =
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
                            "/tmp/" + script);
            assertEquals(0, result.getExitCode(), result.getStderr());
            trace.add(result.getStdout());
        }
        var sql = new JdbcTemplate(new DriverManagerDataSource(url, worker, "fixture"));
        var administrative = new JdbcTemplate(new DriverManagerDataSource(url, react, "fixture"));
        for (String table :
                List.of(
                        "consumption_slot",
                        "partition_ownership",
                        "discovery_checkpoint",
                        "consumption_routing_contract")) {
            assertTrue(
                    sql.queryForObject(
                            "select has_table_privilege(?,'SELECT')", Boolean.class, table));
            assertFalse(
                    sql.queryForObject(
                            "select has_table_privilege(?,'INSERT,TRUNCATE,TRIGGER')",
                            Boolean.class,
                            table));
            assertFalse(
                    sql.queryForObject(
                            "select has_table_privilege(?,'UPDATE')", Boolean.class, table));
        }
        for (String col :
                List.of("claim_token", "claimed_until", "retry_after", "attempt_count", "state"))
            assertTrue(
                    sql.queryForObject(
                            "select has_column_privilege('consumption_slot',?,'UPDATE')",
                            Boolean.class,
                            col));
        for (String col :
                List.of("consumption_id", "consumable_id", "partition_id", "partition_hash"))
            assertFalse(
                    sql.queryForObject(
                            "select has_column_privilege('consumption_slot',?,'UPDATE')",
                            Boolean.class,
                            col));
        assertTrue(
                sql.queryForObject(
                        "select has_table_privilege('consumption_slot','DELETE')", Boolean.class));
        assertFalse(
                administrative.queryForObject(
                        "select has_table_privilege('consumption_slot','DELETE,INSERT')",
                        Boolean.class));
        assertFalse(
                sql.queryForObject(
                        "select pg_has_role(current_user,?,'MEMBER')", Boolean.class, react));
        for (var runtime : List.of(sql, administrative)) {
            assertFalse(
                    runtime.queryForObject(
                            "select has_schema_privilege(current_schema(),'CREATE')",
                            Boolean.class));
            assertThrows(
                    RuntimeException.class,
                    () -> runtime.execute("create table forbidden(id int)"));
            assertThrows(
                    RuntimeException.class,
                    () -> runtime.update("delete from consumption_routing_contract"));
        }
        assertThrows(
                RuntimeException.class,
                () ->
                        sql.update(
                                "insert into"
                                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                    + " values"
                                    + " ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a','00000000-0000-0000-0000-000000000001',0,0)"));
        assertThrows(RuntimeException.class, () -> sql.execute("set role " + react));
        assertNull(sql.queryForObject("select to_regclass('v3_command')::text", String.class));
        trace.add(
                "effective nonadmin worker="
                        + worker
                        + " administrative="
                        + react
                        + " "
                        + sql.queryForMap(
                                "select"
                                    + " current_user,current_database(),current_schema(),clock_timestamp(),version()"));
        trace.add(
                admin.queryForList(
                                "select table_name,column_name,grantee,privilege_type from"
                                        + " information_schema.column_privileges where"
                                        + " table_schema=current_schema() order by"
                                        + " table_name,column_name,grantee,privilege_type")
                        .toString());
        var path = Path.of("target/v3-i3-evidence/read-effective-privileges.txt");
        Files.createDirectories(path.getParent());
        Files.write(path, trace);
    }
}
