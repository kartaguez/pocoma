package com.kartaguez.pocoma.infra.tx.spring.v3;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class V3SchemaIsolationPostgresTest extends PostgresFoundationTestSupport {
    @Test
    void installsOnlyTheExplicitFoundationFixtureOnRealPostgres() {
        assertEquals(17, write.jdbc.queryForObject("show server_version_num", Integer.class) / 10000);
        for (Realm realm : new Realm[] { write, read }) {
            assertEquals(realm.schema, realm.jdbc.queryForObject("select current_schema()", String.class));
            assertEquals(Set.of("flyway_schema_history", "foundation_effect", "foundation_commit_guard", "component_revision"),
                    Set.copyOf(realm.jdbc.queryForList("select table_name from information_schema.tables where table_schema=?",
                            String.class, realm.schema)));
            // Flyway also records schema creation; only versioned migrations count here.
            assertEquals(1, realm.jdbc.queryForObject("select count(*) from flyway_schema_history where success and version is not null", Integer.class));
            assertEquals("1", realm.jdbc.queryForObject("select version from flyway_schema_history where version is not null", String.class));
        }
        assertEquals(0, write.jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public'",
                Integer.class), "No historical bootstrap may populate public");
        assertEquals(0, write.jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name in ('pocoma_read','pocoma_control')",
                Integer.class));
    }

    @Test
    void restartValidatesWithoutReexecutingAndKeepsIndependentHistories() {
        write.jdbc.update("insert into foundation_effect values (?,?)", java.util.UUID.randomUUID(), "sentinel");
        assertTrue(write.flyway.validateWithResult().validationSuccessful);
        assertEquals(0, write.flyway.migrate().migrationsExecuted);
        assertTrue(read.flyway.validateWithResult().validationSuccessful);
        assertEquals(0, read.flyway.migrate().migrationsExecuted);
        assertEquals(1, write.observedEffects());
        assertEquals(0, read.observedEffects());
        assertNotEquals(write.schema, read.schema);
        assertNotSame(write.dataSource, read.dataSource);
        assertNotSame(write.manager, read.manager);
        assertTrue(write.flyway.getConfiguration().isCleanDisabled());
    }
}
