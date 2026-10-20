package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.port.transaction.EventFamily;

/** Explicit CURRENT V29 -> additive V30 upgrade, not a certified baseline. */
@Testcontainers
class EventSequencingUpgradePostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
    @Test void securityDefinerChecksUseInstalledSchemaAndNotCallerOrPublicSchema() {
        // Historical V16 deliberately names public; bootstrap CURRENT there first,
        // then relocate real V29 tables in this disposable DB to test V30 in isolation.
        try(var isolated=new PostgreSQLContainer("postgres:17-alpine")) {
            isolated.start();String schema="step4c_isolated";
            var owner=new DriverManagerDataSource(isolated.getJdbcUrl(),isolated.getUsername(),isolated.getPassword());
            Flyway.configure().dataSource(owner).locations("classpath:db/migration").target("29").load().migrate();
            var admin=new JdbcTemplate(owner);admin.execute("create schema step4c_isolated");
            for(String table:admin.queryForList("select tablename from pg_tables where schemaname='public'",String.class))
                admin.execute("alter table public.\""+table+"\" set schema step4c_isolated");
            admin.execute("create table public.event_sequencing_protocol(singleton boolean,mode text)");
            admin.execute("insert into public.event_sequencing_protocol values (true,'PREPARED')");
            Flyway.configure().dataSource(owner).locations("classpath:db/migration").schemas(schema).defaultSchema(schema).load().migrate();
            var source=new DriverManagerDataSource(isolated.getJdbcUrl()+(isolated.getJdbcUrl().contains("?")?"&":"?")+"currentSchema="+schema,isolated.getUsername(),isolated.getPassword());
            var jdbc=new JdbcTemplate(source);
            EventSequencingSchemaFixture.insert(jdbc,EventFamily.BUSINESS_EVENT,UUID.randomUUID());
            jdbc.execute("update event_sequencing_protocol set mode='ACTIVE'");
            var failure=assertThrows(RuntimeException.class,()->EventSequencingSchemaFixture.insert(jdbc,EventFamily.BUSINESS_EVENT,UUID.randomUUID()));
            assertEquals("23514",EventSequencingPostgresTest.sqlState(failure));
            assertEquals(1,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
            assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
            assertEquals("PREPARED",admin.queryForObject("select mode from public.event_sequencing_protocol",String.class));
            System.out.println("STEP4C INSTALLED_SCHEMA "+jdbc.queryForList("select proname,proconfig from pg_proc where pronamespace='step4c_isolated'::regnamespace and prosecdef"));
        }
    }
    @Test void additiveUpgradePreservesAllFourSourcesAndPreparedCurrentSqlTwice() {
        var dataSource=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var jdbc=new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("29").load().migrate();
        for(var f:EventFamily.values())EventSequencingSchemaFixture.insert(jdbc,f,UUID.randomUUID());
        var before=jdbc.queryForList("select event_id from (select id event_id from business_event_outbox union all select event_id from command_terminal_events union all select event_id from external_identity_binding_facts union all select event_id from user_created_facts) e order by event_id");
        var upgrade=Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        assertEquals(1,upgrade.migrate().migrationsExecuted);
        assertEquals("30",upgrade.info().current().getVersion().getVersion());
        var after=jdbc.queryForList("select event_id from (select id event_id from business_event_outbox union all select event_id from command_terminal_events union all select event_id from external_identity_binding_facts union all select event_id from user_created_facts) e order by event_id");
        assertEquals(before,after);
        for(int pass=0;pass<2;pass++) {
            for(var f:EventFamily.values())EventSequencingSchemaFixture.insert(jdbc,f,UUID.randomUUID());
            assertEquals(0,upgrade.migrate().migrationsExecuted);
            assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from event_position",Integer.class));
            assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
        }
        for(String table:EventSequencingSchemaFixture.SOURCES)assertEquals(3,jdbc.queryForObject("select count(*) from "+table,Integer.class));
        System.out.println("STEP4C UPGRADE V29->V30 preserved="+before+" CURRENT SQL twice; PREPARED, E=0, positions=0");
    }
}
