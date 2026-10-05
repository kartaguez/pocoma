package com.kartaguez.pocoma.infra.persistence.primary.jpa.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RecordedCommandContractionMigrationPostgresTest {
    private static final String COMMAND_ID = "10000000-0000-0000-0000-000000000024";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

    @BeforeEach
    void migrateThroughV23() {
        Flyway baseline = flyway("23", true);
        baseline.clean();
        assertEquals(23, baseline.migrate().migrationsExecuted);
    }

    @Test
    void targetOnlyUpgradeContractsToTheCanonicalSchema() throws Exception {
        execute("""
                insert into recorded_commands
                (command_id,command_type,payload_json,submitted_at,envelope_version,
                 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
                values ('%s','TYPE','payload',now(),2,'issuer','subject',
                        '20000000-0000-0000-0000-000000000024',now() + interval '1 hour','[]'::jsonb)
                """.formatted(COMMAND_ID));
        assertEquals(1, flyway("24", false).migrate().migrationsExecuted);
        assertCanonicalSchema();
        assertEquals(1, scalar("select count(*) from recorded_commands where command_id='" + COMMAND_ID + "'"));
        assertEquals("subject", text("select auth_subject from recorded_commands where command_id='" + COMMAND_ID + "'"));
    }

    @Test
    void pendingV1AbortsBeforeAnyDestructiveChange() throws Exception {
        insertV1();
        assertBlockedWithoutMutation();
    }

    @Test
    void terminalV1AlsoAbortsBeforeAnyDestructiveChange() throws Exception {
        insertV1();
        execute("""
                insert into command_outcomes(command_id,outcome_type,public_code,resolved_at)
                values ('%s','REJECTED','BUSINESS_CONFLICT',now())
                """.formatted(COMMAND_ID));
        execute("""
                insert into command_terminal_events
                (event_id,event_type,command_id,command_partition_hash,recorded_at)
                values ('30000000-0000-0000-0000-000000000024','COMMAND_REJECTED','%s',1,now())
                """.formatted(COMMAND_ID));
        assertBlockedWithoutMutation();
        assertEquals(1, scalar("select count(*) from command_outcomes where command_id='" + COMMAND_ID + "'"));
    }

    @Test
    void invalidTargetShapeAbortsBeforeAnyDestructiveChange() throws Exception {
        execute("""
                insert into recorded_commands
                (command_id,command_type,payload_json,submitted_at,envelope_version,
                 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
                values ('%s','TYPE','payload',now(),2,'issuer','subject',
                        '20000000-0000-0000-0000-000000000024',now() + interval '1 hour','[42]'::jsonb)
                """.formatted(COMMAND_ID));
        assertBlockedWithoutMutation();
    }

    private static void insertV1() throws Exception {
        execute("""
                insert into recorded_commands
                (command_id,command_type,payload_json,submitted_at,auth_user_id,auth_issuer,
                 auth_authenticated_at,auth_issued_at,auth_valid_until,auth_permissions_json)
                values ('%s','TYPE','payload',now(),'20000000-0000-0000-0000-000000000024',
                        'issuer',now(),now(),now() + interval '1 hour','[]'::jsonb)
                """.formatted(COMMAND_ID));
    }

    private static void assertBlockedWithoutMutation() throws Exception {
        assertThrows(FlywayException.class, () -> flyway("24", false).migrate());
        assertEquals(1, scalar("select count(*) from recorded_commands where command_id='" + COMMAND_ID + "'"));
        assertEquals(1, scalar("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='recorded_commands'
                  and column_name='auth_user_id'
                """));
        assertEquals(0, scalar("select count(*) from flyway_schema_history where version='24' and success"));
    }

    private static void assertCanonicalSchema() throws Exception {
        assertEquals(9, scalar("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='recorded_commands'
                """));
        assertEquals(0, scalar("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='recorded_commands'
                  and is_nullable='YES'
                """));
        assertTrue(text("select auth_external_authorities_json::text from recorded_commands limit 1").contains("[]"));
    }

    private static Flyway flyway(String target, boolean cleanEnabled) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target).cleanDisabled(!cleanEnabled).load();
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
