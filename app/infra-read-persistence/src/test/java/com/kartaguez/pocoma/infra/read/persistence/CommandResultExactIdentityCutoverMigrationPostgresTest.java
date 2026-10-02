package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CommandResultExactIdentityCutoverMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    private static final String COMMAND = "10000000-0000-0000-0000-000000000014";
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach void prepareV13() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop schema if exists pocoma_read cascade");
        flyway("13").migrate();
    }

    @Test void freshReadStorePasses() {
        assertEquals(1, flyway("14").migrate().migrationsExecuted);
    }

    @Test void exactIdentityArtifactPassesWithoutMutation() {
        insertArtifact("""
                {"commandId":"%s","outcome":"REJECTED","potId":null,
                 "resultingVersion":null,"code":"BUSINESS_CONFLICT","resolvedAt":"2026-01-01T00:00:00Z",
                 "visibility":"EXACT_EXTERNAL_IDENTITY",
                 "visibleToExternalIdentity":{"issuer":"issuer","subject":"subject"}}
                """.formatted(COMMAND));
        assertEquals(1, flyway("14").migrate().migrationsExecuted);
        assertEquals(1, jdbc.queryForObject("select count(*) from pocoma_read.projection_artifact", Integer.class));
    }

    @Test void legacyArtifactBlocksCutoverAndRemainsIntact() {
        insertArtifact("""
                {"commandId":"%s","outcome":"REJECTED","potId":null,
                 "resultingVersion":null,"code":"BUSINESS_CONFLICT","resolvedAt":"2026-01-01T00:00:00Z",
                 "submittedByUserId":"20000000-0000-0000-0000-000000000014"}
                """.formatted(COMMAND));
        assertBlocked();
    }

    @Test void mixedArtifactBlocksCutover() {
        insertArtifact("""
                {"commandId":"%s","outcome":"REJECTED","potId":null,
                 "resultingVersion":null,"code":"BUSINESS_CONFLICT","resolvedAt":"2026-01-01T00:00:00Z",
                 "visibility":"EXACT_EXTERNAL_IDENTITY",
                 "visibleToExternalIdentity":{"issuer":"issuer","subject":"subject"},
                 "submittedByUserId":"20000000-0000-0000-0000-000000000014"}
                """.formatted(COMMAND));
        assertBlocked();
    }

    @Test void malformedExactIdentityBlocksCutover() {
        insertArtifact("""
                {"commandId":"%s","outcome":"REJECTED","potId":null,
                 "resultingVersion":null,"code":"BUSINESS_CONFLICT","resolvedAt":"2026-01-01T00:00:00Z",
                 "visibility":"EXACT_EXTERNAL_IDENTITY",
                 "visibleToExternalIdentity":{"issuer":"issuer","subject":""}}
                """.formatted(COMMAND));
        assertBlocked();
    }

    private void assertBlocked() {
        assertThrows(FlywayException.class, () -> flyway("14").migrate());
        assertEquals(1, jdbc.queryForObject("select count(*) from pocoma_read.projection_artifact", Integer.class));
        assertEquals(13, jdbc.queryForObject("select max(version::int) from pocoma_read.flyway_schema_history where success", Integer.class));
    }

    private void insertArtifact(String payload) {
        jdbc.update("""
                insert into pocoma_read.projection_root
                (projection_type,target_object_type,target_object_id,target_version)
                values ('COMMAND_RESULT','COMMAND',?,1)
                """, COMMAND);
        jdbc.update("""
                insert into pocoma_read.projection_artifact
                (projection_root_id,artifact_type,artifact_key,payload)
                select id,'COMMAND_RESULT',?,?::jsonb from pocoma_read.projection_root
                where projection_type='COMMAND_RESULT' and target_object_id=?
                """, COMMAND, payload, COMMAND);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).defaultSchema("pocoma_read")
                .schemas("pocoma_read").createSchemas(true)
                .locations("classpath:db/read-store/migration").target(target).load();
    }
}
