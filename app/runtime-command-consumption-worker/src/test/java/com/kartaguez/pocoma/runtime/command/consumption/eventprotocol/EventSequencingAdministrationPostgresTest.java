package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.command.consumption.eventprotocol.EventSequencingSchemaFixture.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.port.transaction.EventFamily;

/** Actual maintenance API, independently committed connections and CURRENT PostgreSQL. */
@Testcontainers
class EventSequencingAdministrationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    UUID operation;
    @BeforeEach void setup() {
        source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='step4e_runtime') then create role step4e_runtime login password 'test-only'; end if; end $$");
        jdbc.execute("grant pocoma_event_runtime to step4e_runtime");
        jdbc.execute("truncate users,external_identity_binding_streams,recorded_commands,registration_requests,business_event_outbox,command_terminal_events,external_identity_binding_facts,user_created_facts,event_sequencing_cutovers cascade");
        jdbc.update("update event_sequencing_protocol set mode='PREPARED'");
        jdbc.update("update event_sequence_authorities set last_position=0");
        operation=UUID.randomUUID();
    }
    String activate() {
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->{
            jdbc.execute("set local lock_timeout='2s'");jdbc.execute("set local statement_timeout='30s'");
            return jdbc.queryForObject("select activate_event_sequencing(?, 'step4e_runtime')::text",String.class,operation);
        });
    }
    void healthy() {
        var rows=jdbc.queryForList("select * from inspect_event_sequencing()");
        assertEquals(4,rows.size());rows.forEach(r->assertEquals(true,r.get("valid"),r.toString()));
    }
    void preparedEmpty() {
        assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from event_position",Integer.class));
        assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
        assertEquals(0,jdbc.queryForObject("select count(*) from event_sequencing_cutovers",Integer.class));
    }
    @ParameterizedTest @ValueSource(ints={0,3,1000})
    void completeDeterministicFourFamilyHistoryAndIdempotentRelaunch(int size) {
        for(var family:EventFamily.values()) for(int i=size;i>0;i--) insert(jdbc,family,new UUID(family.ordinal(),i));
        String boundaries=activate();healthy();
        for(var family:EventFamily.values()) {
            assertEquals(java.util.stream.IntStream.rangeClosed(1,size).mapToObj(i->new UUID(family.ordinal(),i)).toList(),
                jdbc.queryForList("select event_id from event_position where family=? order by position",UUID.class,family.name()));
        }
        assertEquals(boundaries,activate());healthy();
        UUID original=operation;operation=UUID.randomUUID();
        assertThrows(RuntimeException.class,this::activate);operation=original;
        assertEquals(1,jdbc.queryForObject("select count(*) from event_sequencing_cutovers",Integer.class));
    }
    @Test void upgradeV30PreparedPreservesHistoricMigrationBaselineFact() throws Exception {
        try(var isolated=new PostgreSQLContainer("postgres:17-alpine")) {
            isolated.start();var ds=new DriverManagerDataSource(isolated.getJdbcUrl(),isolated.getUsername(),isolated.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("30").load().migrate();
            var before=new JdbcTemplate(ds);UUID id=UUID.randomUUID();insert(before,EventFamily.BINDING_FACT,id);
            before.update("update external_identity_binding_streams set current_revision=0");
            before.update("update external_identity_binding_occurrences set attached_revision=0");
            before.update("update external_identity_binding_facts set binding_revision=0,record_origin='MIGRATION_BASELINE'");
            var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertEquals(1,flyway.migrate().migrationsExecuted);assertEquals(0,flyway.migrate().migrationsExecuted);
            new TransactionTemplate(new DataSourceTransactionManager(ds)).executeWithoutResult(s->{
                before.execute("set local lock_timeout='5s'");before.execute("set local statement_timeout='30s'");
                before.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,UUID.randomUUID());
            });
            assertEquals(id,before.queryForObject("select event_id from binding_fact_positions",UUID.class));
            assertEquals("MIGRATION_BASELINE",before.queryForObject("select record_origin from external_identity_binding_facts",String.class));
            before.queryForList("select * from inspect_event_sequencing()").forEach(r->assertEquals(true,r.get("valid")));
        }
    }
    @Test void psqlAdministrativeScriptsExecuteWithQuotedVariables() throws Exception {
        var sql=java.nio.file.Path.of(System.getProperty("user.dir"),"..","..","docs","operations","sql").normalize();
        for(String file:List.of("event-sequencing-inspect.sql","event-sequencing-cutover.sql","event-sequencing-locks.sql"))
            PG.copyFileToContainer(org.testcontainers.utility.MountableFile.forHostPath(sql.resolve(file)),"/tmp/"+file);
        var inspect=PG.execInContainer("psql","-X","-U",PG.getUsername(),"-d",PG.getDatabaseName(),"-v","runtime_role=step4e_runtime","-f","/tmp/event-sequencing-inspect.sql");
        assertEquals(0,inspect.getExitCode(),inspect.getStderr());
        var cutover=PG.execInContainer("psql","-X","-U",PG.getUsername(),"-d",PG.getDatabaseName(),"-v","runtime_role=step4e_runtime",
            "-v","operation_id="+operation,"-v","lock_timeout=2s","-v","statement_timeout=30s","-f","/tmp/event-sequencing-cutover.sql");
        assertEquals(0,cutover.getExitCode(),cutover.getStderr());healthy();
        assertEquals(0,PG.execInContainer("psql","-X","-U",PG.getUsername(),"-d",PG.getDatabaseName(),"-f","/tmp/event-sequencing-locks.sql").getExitCode());
    }
    @Test void exactCommittedPrefixResumesWithoutChangingAnyPair() {
        for(int i=3;i>0;i--)insert(jdbc,EventFamily.BUSINESS_EVENT,new UUID(0,i));
        jdbc.update("insert into business_event_positions values(?,1)",new UUID(0,1));
        jdbc.update("update event_sequence_authorities set last_position=1 where family='BUSINESS_EVENT'");
        activate();healthy();assertEquals(1,jdbc.queryForObject("select position from business_event_positions where event_id=?",Long.class,new UUID(0,1)));
    }
    @ParameterizedTest @ValueSource(strings={"wrong-pair","gap","counter","extra-source"})
    void inconsistentPreparedAssociationsRefuseActivationWithoutRepair(String fault) {
        insert(jdbc,EventFamily.BUSINESS_EVENT,new UUID(0,2));
        insert(jdbc,EventFamily.BUSINESS_EVENT,new UUID(0,3));
        long position=fault.equals("gap")?2:1;
        jdbc.update("insert into business_event_positions values(?,?)",fault.equals("wrong-pair")?new UUID(0,3):new UUID(0,2),position);
        jdbc.update("update event_sequence_authorities set last_position=? where family='BUSINESS_EVENT'",fault.equals("counter")?2:1);
        if(fault.equals("extra-source"))insert(jdbc,EventFamily.BUSINESS_EVENT,new UUID(0,1));
        assertThrows(RuntimeException.class,this::activate);
        assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
        assertEquals(1,jdbc.queryForObject("select count(*) from event_position",Integer.class));
    }
    @Test void missingHistoricalSourceCannotLeaveOrphanAndMissingAssociationIsDetected() {
        insert(jdbc,EventFamily.BUSINESS_EVENT,new UUID(0,1));
        assertEquals(1,jdbc.queryForObject("select missing from inspect_event_sequencing() where family='BUSINESS_EVENT'",Long.class));
        jdbc.update("insert into business_event_positions values(?,1)",new UUID(0,1));
        assertThrows(RuntimeException.class,()->jdbc.update("delete from business_event_outbox"));
        jdbc.update("update event_sequence_authorities set last_position=1 where family='BUSINESS_EVENT'");
        activate();healthy();
    }
    @Test void faultMidBackfillRollsBackAlreadyAssociatedFamiliesAndRelaunches() {
        for(var family:EventFamily.values())insert(jdbc,family,UUID.randomUUID());
        jdbc.execute("create function step4e_fail_backfill() returns trigger language plpgsql as $$ begin raise exception 'injected mid backfill' using errcode='P0001';end $$");
        jdbc.execute("create trigger step4e_fail_backfill after insert on binding_fact_positions for each row execute function step4e_fail_backfill()");
        try {
            var failure=assertThrows(RuntimeException.class,this::activate);
            assertEquals("P0001",EventSequencingPostgresTest.sqlState(failure));preparedEmpty();
        } finally {jdbc.execute("drop trigger step4e_fail_backfill on binding_fact_positions");jdbc.execute("drop function step4e_fail_backfill()");}
        activate();healthy();
    }
    @Test void interruptionBeforeCommitRollsBackAllAndRelaunchCommitsOnce() {
        for(var family:EventFamily.values())insert(jdbc,family,UUID.randomUUID());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(s->{
            jdbc.execute("set local lock_timeout='2s'");jdbc.execute("set local statement_timeout='30s'");
            jdbc.queryForObject("select activate_event_sequencing(?,'step4e_runtime')::text",String.class,operation);
            s.setRollbackOnly();
        });
        preparedEmpty();activate();healthy();
        // Lost client acknowledgement after commit: a fresh connection reconciles the same operation.
        assertEquals(operation,jdbc.queryForObject("select operation_id from event_sequencing_cutovers",UUID.class));
        activate();healthy();
    }
    @Test void disabledConstraintAndUnsafeFunctionRefuseCutover() {
        jdbc.execute("alter table user_created_facts disable trigger user_created_fact_positions_complete");
        try {assertThrows(RuntimeException.class,this::activate);preparedEmpty();}
        finally {jdbc.execute("alter table user_created_facts enable trigger user_created_fact_positions_complete");}
        jdbc.execute("alter function reserve_event_positions(text,bigint) set search_path=public");
        try {assertThrows(RuntimeException.class,this::activate);preparedEmpty();}
        finally {jdbc.execute("alter function reserve_event_positions(text,bigint) set search_path=pg_catalog,public,pg_temp");}
    }
    @Test void restrictedRuntimeAllowedAndForbiddenOperationsAndTransitiveMembership() {
        assertEquals(List.of(),jdbc.queryForList("select finding from audit_event_sequencing_role('step4e_runtime')",String.class));
        for(String sql:List.of("update event_sequencing_protocol set mode='ACTIVE'","update event_sequence_authorities set last_position=9",
            "truncate business_event_positions","alter table business_event_outbox disable trigger all",
            "select activate_event_sequencing('"+operation+"','step4e_runtime')", "create table public.step4e_forbidden(id int)")) {
            assertThrows(RuntimeException.class,()->new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->{
                jdbc.execute("set local role step4e_runtime");jdbc.execute(sql);return null;
            }),sql);
        }
        jdbc.execute("create role step4e_danger nologin noinherit");
        try {
            jdbc.execute("grant create on schema public to step4e_danger");
            jdbc.execute("grant step4e_danger to step4e_runtime with inherit false");
            assertTrue(jdbc.queryForList("select finding from audit_event_sequencing_role('step4e_runtime')",String.class).stream().anyMatch(s->s.contains("schema CREATE")));
            assertThrows(RuntimeException.class,this::activate);preparedEmpty();
        } finally {jdbc.execute("revoke step4e_danger from step4e_runtime");jdbc.execute("drop owned by step4e_danger");jdbc.execute("drop role step4e_danger");}
        activate();healthy();
        // R1 remains explicit: nominal technical privileges alone do not enforce density.
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(s->{
            jdbc.execute("set local role step4e_runtime");
            assertEquals(2L,jdbc.queryForObject("select reserve_event_positions('BUSINESS_EVENT',2)",Long.class));
        });
        assertEquals(false,jdbc.queryForObject("select valid from inspect_event_sequencing() where family='BUSINESS_EVENT'",Boolean.class));
        assertThrows(RuntimeException.class,this::activate);
    }
    @ParameterizedTest @ValueSource(strings={"column","parameter","ownership","maintenance"})
    void effectivePrivilegesCannotHideBehindColumnsParametersOwnershipOrFunctionGrants(String offense) {
        String grant=switch(offense) {
            case "column" -> "grant update(last_position) on event_sequence_authorities to step4e_runtime";
            case "parameter" -> "grant set on parameter session_replication_role to step4e_runtime";
            case "ownership" -> "alter table user_created_fact_positions owner to step4e_runtime";
            default -> "grant execute on function activate_event_sequencing(uuid,name) to step4e_runtime";
        };
        jdbc.execute(grant);
        try {
            assertFalse(jdbc.queryForList("select finding from audit_event_sequencing_role('step4e_runtime')",String.class).isEmpty());
            assertThrows(RuntimeException.class,this::activate);preparedEmpty();
        } finally {
            switch(offense) {
                case "column" -> jdbc.execute("revoke update(last_position) on event_sequence_authorities from step4e_runtime");
                case "parameter" -> jdbc.execute("revoke set on parameter session_replication_role from step4e_runtime");
                case "ownership" -> jdbc.execute("alter table user_created_fact_positions owner to "+PG.getUsername());
                default -> jdbc.execute("revoke execute on function activate_event_sequencing(uuid,name) from step4e_runtime");
            }
        }
        activate();healthy();
    }
    @Test void groupCertificateAlsoAuditsEveryMemberLoginAndItsOtherMemberships() {
        jdbc.execute("alter role step4e_runtime createrole");
        try {
            var issues=jdbc.queryForList("select finding from audit_event_sequencing_role('pocoma_event_runtime')",String.class);
            assertTrue(issues.stream().anyMatch(s->s.contains("step4e_runtime")),issues.toString());
            assertThrows(RuntimeException.class,()->new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->{
                jdbc.execute("set local lock_timeout='2s'");jdbc.execute("set local statement_timeout='30s'");
                return jdbc.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,operation);
            }));preparedEmpty();
        } finally {jdbc.execute("alter role step4e_runtime nocreaterole");}
        activate();healthy();
    }
    @Test void activeProducerTransactionEvenWithoutSourceWriteBlocksCutover() throws Exception {
        try(var connection=new DriverManagerDataSource(PG.getJdbcUrl(),"step4e_runtime","test-only").getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("select 1");
            assertThrows(RuntimeException.class,this::activate);preparedEmpty();connection.rollback();
        }
        activate();healthy();
    }
    @Test void sourceLockProvidesDatabaseExclusionAndBoundedFailure() throws Exception {
        insert(jdbc,EventFamily.BUSINESS_EVENT,UUID.randomUUID());
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("lock business_event_outbox in row exclusive mode");
            assertThrows(RuntimeException.class,this::activate);preparedEmpty();connection.rollback();
        }
        activate();healthy();
    }
    @Test void oldWriterRejectedAfterActivationInEveryFamily() {
        activate();
        for(var family:EventFamily.values()) assertThrows(RuntimeException.class,()->
            new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s->{insert(jdbc,family,UUID.randomUUID());return null;}));
        healthy();
    }
}
