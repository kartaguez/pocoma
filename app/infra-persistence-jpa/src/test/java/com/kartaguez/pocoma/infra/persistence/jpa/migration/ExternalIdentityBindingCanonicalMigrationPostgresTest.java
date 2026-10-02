package com.kartaguez.pocoma.infra.persistence.jpa.migration;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ExternalIdentityBindingCanonicalMigrationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    private static final String U1 = "10000000-0000-0000-0000-000000000001";
    private static final String B1 = "20000000-0000-0000-0000-000000000001";
    private static final String B2 = "20000000-0000-0000-0000-000000000002";

    @BeforeEach void reset() { Flyway f = flyway("20", true); f.clean(); f.migrate(); }

    @Test void activeR0GetsExplicitBaselineAndPermanentReservation() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into users values ('" + U1 + "')");
            s.executeUpdate("insert into external_identity_binding_streams values ('issuer','subject',0)");
            s.executeUpdate("insert into external_identities values ('issuer','subject','" + U1 + "','" + B1 + "')");
        }
        flyway(null, false).migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertEquals(1, count(s, "select count(*) from external_identity_binding_facts where "
                    + "binding_revision=0 and record_origin='MIGRATION_BASELINE' and user_id='" + U1 + "'"));
            assertEquals(1, count(s, "select count(*) from external_identity_binding_occurrences where binding_id='" + B1 + "'"));
            assertThrows(Exception.class, () -> s.executeUpdate("insert into external_identity_binding_occurrences "
                    + "values ('" + B1 + "','other','subject','" + U1 + "',1,now())"));
        }
    }

    @Test void v18UpgradeCarriesExactExistingAssociationIntoR0Baseline() throws Exception {
        Flyway f = flyway("18", true);
        f.clean();
        f.migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into users values ('" + U1 + "')");
            s.executeUpdate("insert into external_identities values ('issuer','subject','" + U1 + "','" + B1 + "')");
        }
        flyway(null, false).migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertEquals(1, count(s, "select count(*) from external_identity_binding_facts where "
                    + "binding_revision=0 and record_origin='MIGRATION_BASELINE' and user_id='" + U1 + "' "
                    + "and binding_id='" + B1 + "'"));
        }
    }

    @Test void detachedR0WithoutVerifiedEvidenceFailsClosedThenRepairsAndReplays() throws Exception {
        historicalDetached(false);
        flyway("21", false).migrate();
        Exception blocked = assertThrows(Exception.class, () -> flyway(null, false).migrate());
        assertTrue(message(blocked).contains("MIGRATION BLOCKED"));
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into external_identity_binding_baseline_evidence values "
                    + "('issuer','subject','" + U1 + "','" + B1 + "','verified-v18-v20-snapshot')");
        }
        flyway(null, false).migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertEquals(1, count(s, "select count(*) from external_identity_binding_facts where "
                    + "binding_revision=0 and record_origin='MIGRATION_BASELINE' and binding_id='" + B1 + "'"));
            assertEquals(1, count(s, "select count(*) from external_identity_binding_facts where "
                    + "binding_revision=1 and fact_type='DETACHED' and user_id='" + U1 + "'"));
            assertEquals(1, count(s, "select count(*) from external_identity_binding_occurrences where binding_id='" + B1 + "'"));
        }
    }

    @Test void detachedR0ThenReattachedHasContiguousHistoryAndBothIdsReserved() throws Exception {
        historicalDetached(true);
        flyway("21", false).migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into external_identity_binding_baseline_evidence values "
                    + "('issuer','subject','" + U1 + "','" + B1 + "','verified-v18-v20-snapshot')");
        }
        flyway(null, false).migrate();
        try (Connection c = connection(); Statement s = c.createStatement()) {
            assertEquals(3, count(s, "select count(*) from external_identity_binding_facts where issuer='issuer' and subject='subject'"));
            assertEquals(2, count(s, "select count(*) from external_identity_binding_occurrences"));
            assertEquals(2, count(s, "select current_revision from external_identity_binding_streams where issuer='issuer' and subject='subject'"));
        }
    }

    private void historicalDetached(boolean reattach) throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.executeUpdate("insert into users values ('" + U1 + "')");
            s.executeUpdate("insert into external_identity_binding_streams values ('issuer','subject',0)");
            s.executeUpdate("insert into external_identities values ('issuer','subject','" + U1 + "','" + B1 + "')");
            s.executeUpdate("delete from external_identities where issuer='issuer' and subject='subject'");
            s.executeUpdate("update external_identity_binding_streams set current_revision=1");
            s.executeUpdate("insert into external_identity_binding_facts "
                    + "(event_id,issuer,subject,binding_revision,fact_type,user_id,binding_id,recorded_at,partition_hash) "
                    + "values (gen_random_uuid(),'issuer','subject',1,'DETACHED',null,'" + B1 + "',now(),42)");
            if (reattach) {
                s.executeUpdate("insert into external_identities values ('issuer','subject','" + U1 + "','" + B2 + "')");
                s.executeUpdate("update external_identity_binding_streams set current_revision=2");
                s.executeUpdate("insert into external_identity_binding_facts "
                        + "(event_id,issuer,subject,binding_revision,fact_type,user_id,binding_id,recorded_at,partition_hash) "
                        + "values (gen_random_uuid(),'issuer','subject',2,'ATTACHED','" + U1 + "','" + B2 + "',now(),42)");
            }
        }
    }
    private static int count(Statement s, String sql) throws Exception {
        try (ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getInt(1); }
    }
    private static String message(Throwable t) {
        StringBuilder b = new StringBuilder();
        while (t != null) { b.append(t.getMessage()); t = t.getCause(); }
        return b.toString();
    }
    private static Flyway flyway(String target, boolean cleanEnabled) {
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(!cleanEnabled);
        if (target != null) config.target(target);
        return config.load();
    }
    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
