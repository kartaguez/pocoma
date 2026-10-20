package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.command.consumption.eventprotocol.EventSequencingSchemaFixture.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.domain.consumption.key.*;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.runtime.consumption.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.BusinessEventEnvelope;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcUserCreatedFactAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.port.transaction.*;

/** V-C: real V30, real four source tables; only producers/composition are test-only. */
@SpringBootTest(classes=PocomaCommandConsumptionWorkerApplication.class,
        properties={"pocoma.command-consumption.enabled=false","spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventSequencingPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle;
    @Autowired @Qualifier("commandConsumptionTerminalParticipant") ConsumptionTerminalParticipant terminal;
    JpaTransactionManager manager;
    JdbcEventTransactionSequencer sequencer;
    PrimaryEventTransactionRunner runner;

    @BeforeEach void setup() {
        manager=(JpaTransactionManager)transactionManager;
        sequencer=new JdbcEventTransactionSequencer(manager);
        runner=new PrimaryEventTransactionRunner(manager,sequencer);
        jdbc.execute("update event_sequencing_protocol set mode='PREPARED'");
        // Maintenance-only cleanup on an isolated disposable database, never production.
        jdbc.execute("truncate business_event_outbox,command_terminal_events,external_identity_binding_facts,user_created_facts cascade");
        jdbc.execute("update event_sequence_authorities set last_position=0");
        jdbc.execute("create schema if not exists proof_4c");
        jdbc.execute("create table if not exists proof_4c.trace(ord bigint generated always as identity,object text,family text,pid int,xid bigint)");
        jdbc.execute("truncate proof_4c.trace restart identity");
        jdbc.execute("""
            create or replace function proof_4c.observe() returns trigger language plpgsql as $$
            begin insert into proof_4c.trace(object,family,pid,xid)
              values(TG_TABLE_NAME,to_jsonb(NEW)->>'family',
                     pg_backend_pid(),txid_current()); return NEW; end $$
            """);
        for (String table:concat(List.of("event_sequence_authorities","consumption_slots","consumption_claims"),Arrays.asList(POSITIONS),Arrays.asList(SOURCES))) {
            jdbc.execute("drop trigger if exists observe_4c on "+table);
            jdbc.execute("create trigger observe_4c after insert or update on "+table+" for each row execute function proof_4c.observe()");
        }
        jdbc.execute("update event_sequencing_protocol set mode='ACTIVE'"); // Isolated test DB only.
    }
    @AfterEach void clean() {
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @SafeVarargs static <T> List<T> concat(List<T>... lists) {
        List<T> result=new ArrayList<>(); for(var list:lists)result.addAll(list); return result;
    }
    Connection connection() throws SQLException {
        var c=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        c.createStatement().execute("set statement_timeout='8s'"); return c;
    }
    int pid() { return jdbc.queryForObject("select pg_backend_pid()",Integer.class); }
    long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }
    void publish(PrimaryEventTransactionRunner owner,EventFamily family,UUID id) {
        owner.requireOpen();
        if(family==EventFamily.BUSINESS_EVENT) {
            new PrimaryEventInsert(owner).persistNew(new JpaBusinessEventOutboxEntity(new BusinessEventEnvelope(
                id,"POT_CREATED",PotId.of(id),id,1,"{}",null,null,java.time.Instant.now())));
        } else insert(jdbc,family,id);
        owner.recordPublishedEvent(new EventRef(family,id));
    }
    List<UUID> positioned(EventFamily f) {
        return jdbc.queryForList("select event_id from "+POSITIONS[f.ordinal()]+" order by position",UUID.class);
    }
    void assertDense(EventFamily f,List<UUID> ids) {
        assertEquals(ids,positioned(f));
        assertEquals(ids.size(),jdbc.queryForObject("select last_position from event_sequence_authorities where family=?",Long.class,f.name()));
        assertEquals(java.util.stream.LongStream.rangeClosed(1,ids.size()).boxed().toList(),
            jdbc.queryForList("select position from "+POSITIONS[f.ordinal()]+" order by position",Long.class));
        assertEquals(new HashSet<>(ids),new HashSet<>(jdbc.queryForList("select "+(f==EventFamily.BUSINESS_EVENT?"id":"event_id")+" from "+SOURCES[f.ordinal()],UUID.class)));
    }
    void assertEmpty() {
        for(var f:EventFamily.values())assertDense(f,List.of());
        assertEquals(0,count("proof_4c.trace"));
    }

    static String sqlState(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SQLException sql)return sql.getSQLState();
        throw new AssertionError("No PostgreSQL error",error);
    }

    @Test void blocksFollowDeclaredOrderNotUuidAndShareJpaJdbcPhysicalTransaction() {
        Map<EventFamily,List<UUID>> accepted=new EnumMap<>(EventFamily.class);
        var sameAcrossFamilies=UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        runner.runInTransaction(()->{
            Object[] jpa=(Object[])EntityManagerFactoryUtils.getTransactionalEntityManager(emf)
                .createNativeQuery("select pg_backend_pid(),txid_current()").getSingleResult();
            var sql=jdbc.queryForMap("select pg_backend_pid() pid,txid_current() xid");
            assertEquals(((Number)jpa[0]).longValue(),((Number)sql.get("pid")).longValue());
            assertEquals(((Number)jpa[1]).longValue(),((Number)sql.get("xid")).longValue());
            for(var f:EventFamily.values())accepted.put(f,new ArrayList<>());
            for(int n=3;n>=1;n--) for(var f:List.of(EventFamily.USER_CREATED_FACT,EventFamily.BINDING_FACT,
                    EventFamily.COMMAND_TERMINAL_EVENT,EventFamily.BUSINESS_EVENT)) {
                // Binding/User parents use distinct IDs; same UUID across Business/Terminal is legal.
                UUID id=(n==3 && (f==EventFamily.BUSINESS_EVENT || f==EventFamily.COMMAND_TERMINAL_EVENT))
                    ?sameAcrossFamilies:UUID.fromString(String.format("00000000-0000-4000-8000-%012d",n*10+f.ordinal()));
                publish(runner,f,id); accepted.get(f).add(id);
            }
            assertEquals(0,count("business_event_outbox"),"business JPA materializes at terminal flush");
        });
        accepted.forEach(this::assertDense);
        assertTrue(jdbc.queryForObject("""
            select (select max(ord) from proof_4c.trace where object in
              ('business_event_outbox','command_terminal_events','external_identity_binding_facts','user_created_facts'))
              < (select min(ord) from proof_4c.trace where object='event_sequence_authorities')
            """,Boolean.class),"all materialized source INSERTs precede first E");
        assertEquals(1,jdbc.queryForObject("select count(distinct(pid,xid)) from proof_4c.trace",Integer.class));
        assertEquals(Arrays.stream(EventFamily.values()).map(Enum::name).toList(),jdbc.queryForList(
            "select family from proof_4c.trace where object='event_sequence_authorities' order by ord",String.class));
        System.out.println("STEP4C PHYSICAL_ORDER "+jdbc.queryForList("select * from proof_4c.trace order by ord"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void realConsumptionFinalizesBeforeEAndSuffixFailureRestoresSlotAndClaim(boolean fail) {
        var protocol=new PrimaryEventTransactionRunner(manager,batch->{
            sequencer.execute(batch);
            if(fail)throw new IllegalStateException("4C failure after real associations");
        });
        var owner=new ConsumptionOwnerTransactionRunner(protocol,true);
        var claim=assertInstanceOf(AcquireResult.Acquired.class,owner.runInTransaction(()->lifecycle.acquire(
            new ConsumptionKey(new ConsumableIdentity("STEP4C",List.of(UUID.randomUUID().toString())),
                new ConsumerIdentity("STEP4C",List.of("1"))),ClaimId.generate(),new WorkerId("step4c"),
                new ClaimLease(Duration.ofSeconds(30)),java.time.Instant.now()))).claim();
        jdbc.execute("truncate proof_4c.trace");
        var service=new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(terminal.finalizer()),owner);
        UUID business=UUID.randomUUID(),binding=UUID.randomUUID();
        var input=new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),ignored->{
            publish(protocol,EventFamily.BINDING_FACT,binding);publish(protocol,EventFamily.BUSINESS_EVENT,business);
            return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),List.of(),List.of());
        });
        if(fail) {
            var error=assertThrows(ConsumptionTransactionResolutionException.class,()->service.execute(input));
            assertEquals(ConsumptionTransactionResolutionException.Phase.COMMIT,error.phase());
            assertEquals(ConsumptionTransactionResolutionException.Outcome.ROLLED_BACK,error.outcome());
            assertEquals("PENDING",jdbc.queryForObject("select status from consumption_slots where slot_id=?",String.class,claim.slotId()));
            assertNull(jdbc.queryForObject("select ended_at from consumption_claims where claim_id=?",java.time.Instant.class,claim.claimId().value()));
            assertEmpty();
        } else {
            service.execute(input);
            assertEquals("DONE",jdbc.queryForObject("select status from consumption_slots where slot_id=?",String.class,claim.slotId()));
            assertNotNull(jdbc.queryForObject("select ended_at from consumption_claims where claim_id=?",java.time.Instant.class,claim.claimId().value()));
            assertDense(EventFamily.BUSINESS_EVENT,List.of(business));assertDense(EventFamily.BINDING_FACT,List.of(binding));
            assertTrue(jdbc.queryForObject("""
                select (select max(ord) from proof_4c.trace where object='consumption_slots')
                     < (select min(ord) from proof_4c.trace where object='consumption_claims')
                   and (select max(ord) from proof_4c.trace where object='consumption_claims')
                     < (select min(ord) from proof_4c.trace where object='event_sequence_authorities')
                """,Boolean.class));
            assertEquals(1,jdbc.queryForObject("select count(distinct(pid,xid)) from proof_4c.trace",Integer.class));
            System.out.println("STEP4C CONSUMPTION "+jdbc.queryForList("select * from proof_4c.trace order by ord"));
        }
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void fictitiousDeclarationRollsBackReservation(EventFamily f) {
        assertEquals("23503",sqlState(assertThrows(RuntimeException.class,()->runner.runInTransaction(()->
            runner.recordPublishedEvent(new EventRef(f,UUID.randomUUID()))))));
        assertEmpty();
    }

    @Test void concurrentOppositeDeclarationsWaitOnPostgresThenObtainCanonicalBlocks() throws Exception {
        var ready=new CountDownLatch(1); var release=new CountDownLatch(1);
        var secondPid=new AtomicInteger(); var firstPid=new AtomicInteger();
        var a=new PrimaryEventTransactionRunner(manager,batch->{
            sequencer.execute(batch);firstPid.set(pid());ready.countDown();await(release);
        });
        var pool=Executors.newFixedThreadPool(2);
        Map<EventFamily,UUID> first=new EnumMap<>(EventFamily.class), second=new EnumMap<>(EventFamily.class);
        for(var f:EventFamily.values()){first.put(f,UUID.randomUUID());second.put(f,UUID.randomUUID());}
        try {
            var fa=pool.submit(()->a.runInTransaction(()->{
                for(var f:EventFamily.values())publish(a,f,first.get(f));
            }));
            assertTrue(ready.await(8,TimeUnit.SECONDS));
            var fb=pool.submit(()->runner.runInTransaction(()->{
                secondPid.set(pid());
                for(var f:List.of(EventFamily.USER_CREATED_FACT,EventFamily.BINDING_FACT,
                    EventFamily.COMMAND_TERMINAL_EVENT,EventFamily.BUSINESS_EVENT))publish(runner,f,second.get(f));
            }));
            awaitBlocked(secondPid,firstPid.get());
            assertEquals(0,count("event_position"),"first transaction has not committed");
            System.out.println("STEP4C CONTENTION "+jdbc.queryForList(
                "select pid,query,wait_event_type,wait_event,pg_blocking_pids(pid) blockers from pg_stat_activity where pid=?",secondPid.get()));
            release.countDown(); fa.get(10,TimeUnit.SECONDS);fb.get(10,TimeUnit.SECONDS);
            for(var f:EventFamily.values())assertDense(f,List.of(first.get(f),second.get(f)));
            assertEquals(Arrays.stream(EventFamily.values()).map(Enum::name).toList(),jdbc.queryForList(
                "select family from proof_4c.trace where object='event_sequence_authorities' and pid=? order by ord",String.class,secondPid.get()));
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
    static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("barrier timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    void awaitBlocked(AtomicInteger waiting,int blocker) {
        assertTimeoutPreemptively(Duration.ofSeconds(6),()->{
            while(waiting.get()==0 || !Boolean.TRUE.equals(jdbc.queryForObject(
                "select ?=any(pg_blocking_pids(?))",Boolean.class,blocker,waiting.get())))Thread.onSpinWait();
        });
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void rollbackAfterEachReservationAndAssociationLeavesNoHole(EventFamily failureFamily) {
        for(String stage:List.of("reservation","association")) {
            String table=stage.equals("reservation")?"event_sequence_authorities":POSITIONS[failureFamily.ordinal()];
            String condition=stage.equals("reservation")?"NEW.family='"+failureFamily.name()+"'":"true";
            jdbc.execute("create or replace function proof_4c.fail() returns trigger language plpgsql as $$ begin if "+condition+
                " then raise exception '4C injected after "+stage+"'; end if; return NEW; end $$");
            jdbc.execute("create trigger fail_4c after "+(stage.equals("reservation")?"update":"insert")+" on "+table+
                " for each row execute function proof_4c.fail()");
            try {
                var error=assertThrows(RuntimeException.class,()->runner.runInTransaction(()->{
                    for(var f:EventFamily.values())publish(runner,f,UUID.randomUUID());
                }));
                assertTrue(error.toString().contains("4C injected after "+stage),error.toString());
                assertEmpty();
            } finally {jdbc.execute("drop trigger fail_4c on "+table);}
        }
        var ids=new EnumMap<EventFamily,List<UUID>>(EventFamily.class);
        runner.runInTransaction(()->{for(var f:EventFamily.values()){
            UUID id=UUID.randomUUID();publish(runner,f,id);ids.put(f,List.of(id));
        }});
        ids.forEach(this::assertDense);
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void completenessIsDeferredAndRejectsMissingAssociationOnCommit(EventFamily f) {
        var completion=new ArrayList<Integer>();
        var inserted=new java.util.concurrent.atomic.AtomicBoolean();
        var error=assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(status->{
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int value){completion.add(value);}
            });
            insert(jdbc,f,UUID.randomUUID());
            assertEquals(1,count(SOURCES[f.ordinal()]),"INSERT succeeds before deferred commit check");inserted.set(true);return null;
        }));
        assertTrue(inserted.get(),"real INSERT reached deferred commit validation");
        assertEquals("23514",sqlState(error));
        assertEquals(List.of(TransactionSynchronization.STATUS_ROLLED_BACK),completion);
        assertEmpty();
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void fkUniquePositionAndUuidAreStrict(EventFamily f) {
        UUID original=UUID.randomUUID();
        runner.runInTransaction(()->publish(runner,f,original));
        assertEquals("23505",sqlState(assertThrows(RuntimeException.class,()->jdbc.update("insert into "+POSITIONS[f.ordinal()]+" values (?,2)",original))));
        assertEquals("23503",sqlState(assertThrows(RuntimeException.class,()->jdbc.update("insert into "+POSITIONS[f.ordinal()]+" values (?,2)",UUID.randomUUID()))));
        assertEquals("23505",sqlState(assertThrows(RuntimeException.class,()->runner.runInTransaction(()->publish(runner,f,original)))));
        assertEquals("23505",sqlState(assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).execute(status->{
            UUID id=UUID.randomUUID();insert(jdbc,f,id);
            jdbc.update("insert into "+POSITIONS[f.ordinal()]+" values (?,1)",id);return null;
        }))));
        assertDense(f,List.of(original));
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void overflowRollsBackEveryFamilyAndAllowsLastRepresentablePosition(EventFamily f) {
        jdbc.update("update event_sequence_authorities set last_position=? where family=?",Long.MAX_VALUE-1,f.name());
        var failure=assertThrows(RuntimeException.class,()->runner.runInTransaction(()->{
            for(var family:EventFamily.values())for(int n=0;n<2;n++)publish(runner,family,UUID.randomUUID());
        }));
        assertTrue(failure.toString().contains("capacity exceeded"),failure.toString());
        assertEquals(0,count("event_position"));
        for(var family:EventFamily.values()) assertEquals(family==f?Long.MAX_VALUE-1:0,
            jdbc.queryForObject("select last_position from event_sequence_authorities where family=?",Long.class,family.name()));
        runner.runInTransaction(()->publish(runner,f,UUID.randomUUID()));
        assertEquals(List.of(Long.MAX_VALUE),jdbc.queryForList("select position from "+POSITIONS[f.ordinal()],Long.class));
        assertThrows(RuntimeException.class,()->runner.runInTransaction(()->publish(runner,f,UUID.randomUUID())));
        assertEquals(1,count("event_position"));
    }

    @Test void preparedAndEmptyLotsNeverAccessAuthoritiesEvenUnderExclusiveTableLock() throws Exception {
        try(var lock=connection()) {
            lock.setAutoCommit(false);lock.createStatement().execute("lock table event_sequence_authorities in access exclusive mode");
            runner.runInTransaction(()->{
                jdbc.execute("set local lock_timeout='300ms'");
                jdbc.update("insert into proof_4c.trace(object,pid,xid) values ('empty-effect',pg_backend_pid(),txid_current())");
                assertNoAuthorityLock(pid());
            });
            jdbc.execute("update event_sequencing_protocol set mode='PREPARED'");
            var prepared=new PrimaryEventTransactionRunner(manager,batch->{sequencer.execute(batch);assertNoAuthorityLock(pid());});
            prepared.runInTransaction(()->{
                jdbc.execute("set local lock_timeout='300ms'");
                for(var f:EventFamily.values())publish(prepared,f,UUID.randomUUID());
            });
            assertEquals(0,count("event_position"));lock.rollback();
        }
        assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
        assertEquals(4,Arrays.stream(SOURCES).mapToLong(this::count).sum());
    }
    void assertNoAuthorityLock(int pid) {
        assertEquals(0,jdbc.queryForObject("select count(*) from pg_locks where pid=? and relation='event_sequence_authorities'::regclass",Integer.class,pid));
    }

    @Test void runtimeRoleCanReserveAndInsertButCannotMutateOrDisarmIntegrity() throws Exception {
        // Successful sequencer path under actual restricted PostgreSQL role.
        var restricted=new PrimaryEventTransactionRunner(manager,batch->{
            jdbc.execute("set local role pocoma_event_runtime");sequencer.execute(batch);
        });
        // Probe triggers are test-only and must not add extra runtime grants/locks.
        for(String table:concat(List.of("event_sequence_authorities"),Arrays.asList(POSITIONS)))jdbc.execute("drop trigger observe_4c on "+table);
        UUID id=UUID.randomUUID();restricted.runInTransaction(()->{
            publish(restricted,EventFamily.BUSINESS_EVENT,id);
            for(var f:List.of(EventFamily.COMMAND_TERMINAL_EVENT,EventFamily.BINDING_FACT,EventFamily.USER_CREATED_FACT))publish(restricted,f,UUID.randomUUID());
        });
        assertEquals(List.of(id),positioned(EventFamily.BUSINESS_EVENT));
        for(String sql:List.of("update business_event_positions set position=2", "delete from business_event_positions",
            "truncate business_event_positions", "update event_sequence_authorities set last_position=0",
            "delete from event_sequence_authorities", "update event_sequencing_protocol set mode='PREPARED'",
            "alter table business_event_outbox disable trigger all", "set session_replication_role=replica",
            "drop function check_business_event_positions_complete() cascade")) {
            try(var c=connection()) {
                c.createStatement().execute("set role pocoma_event_runtime");
                SQLException e=assertThrows(SQLException.class,()->c.createStatement().execute(sql));
                assertEquals("42501",e.getSQLState(),sql+": "+e);
            }
        }
        for(var table:POSITIONS)for(String verb:List.of("update "+table+" set position=2","delete from "+table))
            assertThrows(RuntimeException.class,()->jdbc.execute(verb)); // immutable even for maintenance owner DML
    }

    @Test void deferredChecksAreIndexedAndNeedNoBusinessLocksAtCommit() throws Exception {
        var inSuffix=new CountDownLatch(1);var finish=new CountDownLatch(1);var backend=new AtomicInteger();
        var owner=new PrimaryEventTransactionRunner(manager,batch->{
            sequencer.execute(batch);backend.set(pid());inSuffix.countDown();await(finish);
            var before=businessLocks(pid());
            jdbc.execute("set constraints all immediate"); // Execute the actual deferred checks under E.
            assertEquals(before,businessLocks(pid()),"completeness adds no source/parent/P/B/S lock");
            System.out.println("STEP4C DEFERRED_BUSINESS_LOCKS unchanged="+before);
        });
        var pool=Executors.newSingleThreadExecutor();
        try(var blocker=connection()) {
            var task=pool.submit(()->owner.runInTransaction(()->{
                jdbc.execute("set local lock_timeout='500ms'");
                for(var f:EventFamily.values())publish(owner,f,UUID.randomUUID());
            }));
            assertTrue(inSuffix.await(8,TimeUnit.SECONDS));blocker.setAutoCommit(false);
            // P/B/S tables unused by these synthetic producers must remain untouched
            // during deferred validation; an unexpected table read would time out.
            blocker.createStatement().execute("lock table pot_global_versions,consumption_slots,consumption_claims,external_identities in access exclusive mode");
            var before=jdbc.queryForList("select relation::regclass::text relation,mode from pg_locks where pid=? and relation is not null order by relation,mode",backend.get());
            finish.countDown();task.get(8,TimeUnit.SECONDS);blocker.rollback();
            System.out.println("STEP4C LOCKS_BEFORE_COMMIT "+before);
        } finally {finish.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(4,count("event_position"));
        // Populate enough associations to let the real planner choose the UUID index
        // without enable_seqscan=off; evaluate actual hit and miss existence probes.
        jdbc.execute("update event_sequencing_protocol set mode='PREPARED'");
        for(int n=0;n<POSITIONS.length;n++) {
            String table=POSITIONS[n];
            // Populate real sources/associations; no disabled planner path or fake index.
            EventFamily f=EventFamily.values()[n];
            new TransactionTemplate(manager).execute(status->{
                for(int i=2;i<=3000;i++){UUID id=UUID.randomUUID();insert(jdbc,f,id);jdbc.update("insert into "+table+" values (?,?)",id,i);}return null;
            });
            jdbc.execute("analyze "+table);
            for(UUID probe:List.of(positioned(f).getFirst(),UUID.randomUUID())) {
                String plan=String.join("\n",jdbc.queryForList("explain (analyze,buffers) select 1 from "+table+" where event_id=?",String.class,probe));
                assertTrue(plan.contains("Index") && plan.contains("event_id"),plan);
                System.out.println("STEP4C INDEX "+table+" "+plan);
            }
        }
        assertEquals(4,jdbc.queryForObject("select count(*) from pg_trigger where tgname like '%positions_complete' and tgdeferrable and tginitdeferred",Integer.class));
    }

    List<Map<String,Object>> businessLocks(int backend) {
        return jdbc.queryForList("""
            select relation::regclass::text relation,mode from pg_locks where pid=? and relation in
              ('business_event_outbox'::regclass,'command_terminal_events'::regclass,
               'external_identity_binding_facts'::regclass,'user_created_facts'::regclass,
               'pot_global_versions'::regclass,'consumption_slots'::regclass,'consumption_claims'::regclass,
               'external_identities'::regclass,'users'::regclass,'external_identity_binding_streams'::regclass,
               'external_identity_binding_occurrences'::regclass,'recorded_commands'::regclass,
               'command_outcomes'::regclass,'registration_requests'::regclass) order by relation,mode
            """,backend);
    }

    @Test void waitingTransactionReusesRolledBackBlockAfterSuccessfulSuffix() throws Exception {
        var reserved=new CountDownLatch(1);var abort=new CountDownLatch(1);var firstPid=new AtomicInteger();
        var secondPid=new AtomicInteger();
        var doomed=new PrimaryEventTransactionRunner(manager,batch->{
            sequencer.execute(batch);firstPid.set(pid());reserved.countDown();await(abort);
            throw new IllegalStateException("rollback after complete suffix");
        });
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->assertThrows(IllegalStateException.class,()->doomed.runInTransaction(()->{
                for(var f:EventFamily.values())publish(doomed,f,UUID.randomUUID());
            })));
            assertTrue(reserved.await(8,TimeUnit.SECONDS));
            Map<EventFamily,List<UUID>> surviving=new EnumMap<>(EventFamily.class);
            var second=pool.submit(()->runner.runInTransaction(()->{
                secondPid.set(pid());
                for(var f:EventFamily.values()) {
                    var ids=List.of(UUID.randomUUID(),UUID.randomUUID());surviving.put(f,ids);
                    ids.forEach(id->publish(runner,f,id));
                }
            }));
            awaitBlocked(secondPid,firstPid.get());abort.countDown();
            assertEquals("rollback after complete suffix",first.get(10,TimeUnit.SECONDS).getMessage());
            second.get(10,TimeUnit.SECONDS);surviving.forEach(this::assertDense);
        } finally {abort.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @ParameterizedTest @EnumSource(EventFamily.class)
    void onlyNonemptyFamilyAcquiresItsAuthority(EventFamily f) throws Exception {
        try(var c=connection()) {
            c.setAutoCommit(false);
            try(var lock=c.prepareStatement("select family from event_sequence_authorities where family<>? for update")) {
                lock.setString(1,f.name());lock.executeQuery().close();
            }
            UUID id=UUID.randomUUID();
            runner.runInTransaction(()->{jdbc.execute("set local lock_timeout='300ms'");publish(runner,f,id);});
            assertDense(f,List.of(id));
            assertEquals(List.of(f.name()),jdbc.queryForList("select family from proof_4c.trace where object='event_sequence_authorities' order by ord",String.class));
            c.rollback();
        }
    }

    @Test void reservationRejectsInvalidInputPreparedAndMissingAuthority() {
        for(String args:List.of("'BUSINESS_EVENT',0","'BUSINESS_EVENT',-1","'BUSINESS_EVENT',null","null,1","'UNKNOWN',1"))
            assertEquals("22023",sqlState(assertThrows(RuntimeException.class,()->jdbc.execute("select reserve_event_positions("+args+")"))));
        jdbc.execute("update event_sequencing_protocol set mode='PREPARED'");
        assertEquals("55000",sqlState(assertThrows(RuntimeException.class,()->jdbc.execute("select reserve_event_positions('BUSINESS_EVENT',1)"))));
        jdbc.execute("update event_sequencing_protocol set mode='ACTIVE'");
        jdbc.execute("delete from event_sequence_authorities where family='BUSINESS_EVENT'");
        assertEquals("22003",sqlState(assertThrows(RuntimeException.class,()->runner.runInTransaction(()->publish(runner,EventFamily.BUSINESS_EVENT,UUID.randomUUID())))));
        assertEquals(0,count("business_event_outbox"));assertEquals(0,count("event_position"));
        jdbc.execute("insert into event_sequence_authorities values ('BUSINESS_EVENT',0)");
    }

    @Test void preparedSupportsGuardedCurrentWriterRepeatedlyAndStartupCannotActivate() {
        jdbc.execute("update event_sequencing_protocol set mode='PREPARED'");
        for(int pass=0;pass<2;pass++) {
            UUID id=UUID.randomUUID();
            runner.runInTransaction(()->{
                userParents(jdbc,id);
                runner.recordPublishedEvent(new JdbcUserCreatedFactAdapter(jdbc).append(id,new PocomaUserId(id)));return null;
            });
            assertEquals("PREPARED",new JdbcEventTransactionSequencer(manager).verifyCompatibleMode());
            assertTrue(context.getBeansOfType(JdbcEventTransactionSequencer.class).isEmpty());
            assertTrue(context.getBeansOfType(PrimaryEventTransactionRunner.class).isEmpty());
        }
        assertEquals(2,count("user_created_facts"));assertEquals(0,count("event_position"));
        assertEquals(0,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
    }
}
