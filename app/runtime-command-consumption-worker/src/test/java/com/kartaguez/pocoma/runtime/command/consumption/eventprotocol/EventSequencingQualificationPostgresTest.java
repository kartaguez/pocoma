package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;


import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.contracts.command.*;
import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.pot.event.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.consume.command.consumption.CommandConsumptionExecution;
import com.kartaguez.pocoma.engine.consume.command.pot.publication.AppendBusinessEventService;
import com.kartaguez.pocoma.engine.consume.command.model.*;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.engine.consume.registration.*;
import com.kartaguez.pocoma.engine.port.in.consumption.input.*;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandOutcomeAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.port.transaction.*;
import com.kartaguez.pocoma.port.binding.authority.*;
import com.kartaguez.pocoma.runtime.publication.PrimaryEventProtocol;
import com.kartaguez.pocoma.supra.consume.command.CommandConsumptionKeys;


/** Reproducible characterization through real producers. No business SLO is invented.
 * Command sample includes admission persistence + acquire + real fenced TE; Registration
 * sample is its real producer service under the PRIMARY owner, without a polling worker.
 */
@SpringBootTest(classes=PocomaCommandConsumptionWorkerApplication.class, properties={
    "pocoma.command-consumption.enabled=false","spring.datasource.hikari.maximum-pool-size=16"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(EventSequencingQualificationPostgresTest.RegistrationComposition.class)
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventSequencingQualificationPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine").withCommand("postgres","-c","track_commit_timestamp=on");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);
    }
    @TestConfiguration(proxyBeanMethods=false) static class RegistrationComposition {
        @Bean ExecuteRegistrationService qualificationRegistration(RegistrationRequestReader requests,
            RegistrationOutcomeRepository outcomes,UserAuthorityPort users,ExternalIdentityBindingPort bindings,
            UserCreatedFactPort facts,PublishedEvents declarations) {
            return new ExecuteRegistrationService(requests,outcomes,users,bindings,facts,declarations);
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PrimaryEventProtocol protocol;
    @Autowired AppendBusinessEventService append;
    @Autowired JpaPotCommandEventAppendAdapter business;
    @Autowired PublishedEvents declarations;
    @Autowired ExecuteRegistrationService registration;
    @Autowired JdbcRegistrationRequestStore requests;
    @Autowired JpaUserAuthorityAdapter users;
    @Autowired JpaExternalIdentityBindingAdapter bindings;
    @Autowired RecordedCommandInsertionPort commands;
    @Autowired CommandConsumptionExecution execution;
    @Autowired @Qualifier("commandConsumptionAcquire") AcquireConsumptionUseCase acquire;
    @Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    final java.util.concurrent.ConcurrentLinkedQueue<Long> commandTeNanos=new java.util.concurrent.ConcurrentLinkedQueue<>();
    static final List<String> SOURCES=List.of("business_event_outbox","command_terminal_events","external_identity_binding_facts","user_created_facts");
    void reset(boolean active) {
        jdbc.update("update event_sequencing_protocol set mode='PREPARED'");
        jdbc.execute("truncate users,external_identity_binding_streams,recorded_commands,registration_requests,consumption_slots,business_event_outbox,command_terminal_events,external_identity_binding_facts,user_created_facts,event_sequencing_cutovers cascade");
        jdbc.update("update event_sequence_authorities set last_position=0");
        jdbc.execute("create table if not exists step4e_e_timing(xid bigint,acquired_at timestamptz)");
        jdbc.execute("create or replace function step4e_time_e() returns trigger language plpgsql as $$ begin insert into step4e_e_timing values(txid_current(),clock_timestamp());return new;end $$");
        jdbc.execute("drop trigger if exists step4e_time_e on event_sequence_authorities");
        jdbc.execute("create trigger step4e_time_e after update on event_sequence_authorities for each row execute function step4e_time_e()");
        if(active) new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("set local lock_timeout='5s'");jdbc.execute("set local statement_timeout='60s'");
            jdbc.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,UUID.randomUUID());
        });
    }
    long[] counts(){return SOURCES.stream().mapToLong(t->jdbc.queryForObject("select count(*) from "+t,Long.class)).toArray();}
    void healthy(){var rows=jdbc.queryForList("select * from inspect_event_sequencing()");assertEquals(4,rows.size());rows.forEach(r->assertEquals(true,r.get("valid"),r.toString()));}
    void registration() {
        UUID id=UUID.randomUUID();var identity=new ExternalIdentity("step4e",id.toString());
        protocol.runner().runInTransaction(()->{requests.insert(new RegistrationRequest(id,identity,"{}",Instant.now()));return null;});
        protocol.runner().runInTransaction(()->registration.execute(id));
    }
    void command() {
        UUID id=UUID.randomUUID();var identity=new ExternalIdentity("step4e",id.toString());var user=new PocomaUserId(UUID.randomUUID());
        BindingId binding=protocol.runner().runInTransaction(()->{users.create(new User(user));var value=bindings.acquire(identity,user);value.fact().ifPresent(declarations::recordPublishedEvent);return value.result().bindingId();});
        var command=new RecordedCommand(new CommandId(id),new CommandType("POT_CREATE_V1"),
            "{\"label\":\"4E\",\"creatorId\":\""+user.value()+"\"}",Instant.now(),
            new TargetCommandEnvelope(identity,binding,new CommandAuthenticationEvidence(Set.of("pocoma:pot:create"),Instant.now().plusSeconds(300))));
        protocol.runner().runInTransaction(()->commands.insert(command));
        var claim=((AcquireResult.Acquired)acquire.acquire(new AcquireConsumptionInput(CommandConsumptionKeys.forCommand(command.commandId()),new WorkerId("4e"),new ClaimLease(Duration.ofSeconds(60))))).claim();
        long entered=System.nanoTime();
        execute.execute(new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),execution.forCommand(command.commandId())));
        commandTeNanos.add(System.nanoTime()-entered);
    }
    void workload(String kind,int index) {
        switch(kind) {
            case "COMMAND" -> command();
            case "REGISTRATION" -> registration();
            case "COMMAND_REGISTRATION" -> {if(index%2==0)command();else registration();}
            case "BATCH4" -> protocol.runner().runInTransaction(()->{
                var accepted=business.appendAll(java.util.stream.IntStream.range(0,4).mapToObj(i->(com.kartaguez.pocoma.domain.event.BusinessEvent)new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)).toList());
                accepted.events().forEach(declarations::recordPublishedEvent);return null;
            });
            case "ROLLBACK" -> {
                try {protocol.runner().runInTransaction(()->{append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));throw new IllegalArgumentException("intentional workload rollback");});}
                catch(IllegalArgumentException expected) {assertEquals("intentional workload rollback",expected.getMessage());}
            }
            case "LONG" -> protocol.runner().runInTransaction(()->{
                append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
                // Explicit 20ms workload think-time before suffix, not a concurrency proof.
                try {new java.util.concurrent.CountDownLatch(1).await(20,java.util.concurrent.TimeUnit.MILLISECONDS);}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;
            });
            default -> append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        }
    }
    static double quantile(List<Long> values,double q) {var sorted=values.stream().sorted().toList();return sorted.get(Math.max(0,(int)Math.ceil(q*sorted.size())-1))/1e6;}
    @Test @Order(Integer.MAX_VALUE) void postgresRestartPreservesDenseHistoryAndAllowsNextPublication() throws Exception {
        reset(true);UUID id=append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        org.testcontainers.DockerClientFactory.instance().client().restartContainerCmd(PG.getContainerId()).exec();
        // Docker may reassign an ephemeral published port on restart; inspect the actual endpoint.
        var info=org.testcontainers.DockerClientFactory.instance().client().inspectContainerCmd(PG.getContainerId()).exec();
        String port=info.getNetworkSettings().getPorts().getBindings().get(com.github.dockerjava.api.model.ExposedPort.tcp(5432))[0].getHostPortSpec();
        String url="jdbc:postgresql://"+PG.getHost()+":"+port+"/"+PG.getDatabaseName();
        var restored=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,PG.getUsername(),PG.getPassword()));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(()->restored.queryForObject("select 1",Integer.class)==1);
        restored.queryForList("select * from inspect_event_sequencing()").forEach(r->assertEquals(true,r.get("valid")));
        assertEquals(1,restored.queryForObject("select count(*) from business_event_positions where event_id=?",Integer.class,id));
        try(var restarted=new org.springframework.boot.builder.SpringApplicationBuilder(PocomaCommandConsumptionWorkerApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE).run("--spring.datasource.url="+url,
                "--spring.datasource.username="+PG.getUsername(),"--spring.datasource.password="+PG.getPassword(),
                "--pocoma.command-consumption.enabled=false")) {
            restarted.getBean(AppendBusinessEventService.class).append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        }
        restored.queryForList("select * from inspect_event_sequencing()").forEach(r->assertEquals(true,r.get("valid")));
        assertEquals(2,restored.queryForObject("select last_position from event_sequence_authorities where family='BUSINESS_EVENT'",Long.class));
        System.out.println("STEP4E RECOVERY PostgreSQL container restart: dense history retained, next position=2");
    }
    @Test void restorablePreparedBackupRehearsesCutoverOnSeparateDatabase() throws Exception {
        reset(false);for(int i=0;i<3;i++)append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        assertEquals(0,PG.execInContainer("pg_dump","-U",PG.getUsername(),"-d",PG.getDatabaseName(),"-Fc","-f","/tmp/step4e.dump").getExitCode());
        assertEquals(0,PG.execInContainer("createdb","-U",PG.getUsername(),"step4e_restore").getExitCode());
        try {
            var restored=PG.execInContainer("pg_restore","-U",PG.getUsername(),"--exit-on-error","-d","step4e_restore","/tmp/step4e.dump");
            assertEquals(0,restored.getExitCode(),restored.getStderr());
            var target=new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl().replace("/"+PG.getDatabaseName(),"/step4e_restore"),PG.getUsername(),PG.getPassword());
            var check=new JdbcTemplate(target);
            new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(target)).executeWithoutResult(s->{
                check.execute("set local lock_timeout='5s'");check.execute("set local statement_timeout='60s'");
                check.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,UUID.randomUUID());
            });
            assertEquals(jdbc.queryForList("select id from business_event_outbox order by id",UUID.class),check.queryForList("select event_id from business_event_positions order by position",UUID.class));
            check.queryForList("select * from inspect_event_sequencing()").forEach(r->assertEquals(true,r.get("valid")));
            assertEquals("PREPARED",jdbc.queryForObject("select mode from event_sequencing_protocol",String.class));
            System.out.println("STEP4E RECOVERY pg_dump/pg_restore PREPARED copy then actual cutover: exact UUIDs retained");
        } finally {assertEquals(0,PG.execInContainer("dropdb","-U",PG.getUsername(),"--force","step4e_restore").getExitCode());}
    }
    @Test void actualProducerLockTimeoutRollsBackAndNextOwnerReusesBlock() throws Exception {
        reset(true);
        try(var blocker=jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);blocker.createStatement().execute("select 1 from event_sequence_authorities where family='BUSINESS_EVENT' for update");
            var failure=assertThrows(RuntimeException.class,()->protocol.runner().runInTransaction(()->{
                jdbc.execute("set local lock_timeout='150ms'");append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));return null;
            }));
            assertEquals("55P03",EventSequencingPostgresTest.sqlState(failure));blocker.rollback();
        }
        assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));healthy();
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));healthy();
        assertEquals(1,jdbc.queryForObject("select max(position) from business_event_positions",Long.class));
    }
    @Test void realProducersContinueAfterEveryNonemptyHistoricalBoundary() {
        reset(false);command();registration();
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        var historical=counts();for(long n:historical)assertTrue(n>0);
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("set local lock_timeout='5s'");jdbc.execute("set local statement_timeout='60s'");
            jdbc.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,UUID.randomUUID());
        });
        healthy();var old=jdbc.queryForList("select family,event_id,position from event_position order by family,position");
        var newIds=protocol.runner().runInTransaction(()->{
            var accepted=business.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1),new PotDeletedEvent(PotId.of(UUID.randomUUID()),2)));
            accepted.events().forEach(declarations::recordPublishedEvent);return accepted.events().stream().map(EventRef::eventId).toList();
        });
        assertEquals(newIds,jdbc.queryForList("select event_id from business_event_positions where position>? order by position",UUID.class,historical[0]));
        command();registration();healthy();
        for(var pair:old) assertEquals(pair.get("event_id"),jdbc.queryForObject("select event_id from event_position where family=? and position=?",UUID.class,pair.get("family"),pair.get("position")));
        System.out.println("STEP4E HISTORY nonempty boundaries="+java.util.Arrays.toString(historical)+"; actual Command/Registration and ordered business batch continue after N");
    }
    @Test void workerProcessKilledAfterRealSuffixRollsBackAndReusesPosition() throws Exception {
        reset(true);var signal=java.nio.file.Files.createTempFile("step4e-crash-",".signal");java.nio.file.Files.delete(signal);
        var log=java.nio.file.Files.createTempFile("step4e-worker-",".log");
        var builder=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),EventSequencingWorkerCrashProbe.class.getName());
        builder.environment().put("STEP4E_URL",PG.getJdbcUrl());builder.environment().put("STEP4E_USER",PG.getUsername());
        builder.environment().put("STEP4E_PASSWORD",PG.getPassword());builder.environment().put("STEP4E_SIGNAL",signal.toString());
        var process=builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).until(()->java.nio.file.Files.exists(signal)||!process.isAlive());
            assertTrue(java.nio.file.Files.exists(signal),()->"child failed before E: "+read(log));
            String[] observed=java.nio.file.Files.readString(signal).split(",");int pid=Integer.parseInt(observed[0]);UUID id=UUID.fromString(observed[1]);
            assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox where id=?",Integer.class,id));
            process.destroyForcibly();assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(()->jdbc.queryForObject("select count(*) from pg_stat_activity where pid=?",Integer.class,pid)==0);
            healthy();assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
            append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));healthy();
            assertEquals(1,jdbc.queryForObject("select max(position) from business_event_positions",Long.class));
            System.out.println("STEP4E RECOVERY actual worker process killed after E: no durable event, block reused at 1");
        } finally {process.destroyForcibly();java.nio.file.Files.deleteIfExists(signal);}
    }
    static String read(java.nio.file.Path path){try{return java.nio.file.Files.readString(path);}catch(java.io.IOException e){return e.toString();}}
    @Test void actualDeadlockVictimRollsBackSuffixAndNextProducerIsDense() throws Exception {
        reset(true);var eHeld=new java.util.concurrent.CountDownLatch(1);var acquireAdvisory=new java.util.concurrent.CountDownLatch(1);
        var pidA=new java.util.concurrent.atomic.AtomicInteger();
        try(var other=jdbc.getDataSource().getConnection();var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            other.setAutoCommit(false);var statement=other.createStatement();
            statement.execute("set local deadlock_timeout='10s'");
            statement.execute("select pg_advisory_xact_lock(440004)");
            var rs=statement.executeQuery("select pg_backend_pid()");rs.next();int pidB=rs.getInt(1);rs.close();
            var seq=new JdbcEventTransactionSequencer((JpaTransactionManager)manager);
            var owner=new PrimaryEventTransactionRunner((JpaTransactionManager)manager,batch->{
                seq.execute(batch);pidA.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));eHeld.countDown();
                try {assertTrue(acquireAdvisory.await(5,java.util.concurrent.TimeUnit.SECONDS));}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                // Test-only antagonist lock, deliberately outside the allowed producer graph.
                jdbc.execute("set local deadlock_timeout='100ms'");jdbc.execute("select pg_advisory_xact_lock(440004)");
            });
            var writer=new JpaBusinessEventOutboxAdapter(emf,new com.fasterxml.jackson.databind.ObjectMapper());
            var service=new AppendBusinessEventService(writer,owner,owner::recordPublishedEvent);
            var producer=pool.submit(()->assertThrows(RuntimeException.class,()->service.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1))));
            assertTrue(eHeld.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var antagonist=pool.submit(()->{try{statement.execute("select 1 from event_sequence_authorities where family='BUSINESS_EVENT' for update");}
                catch(java.sql.SQLException e){throw new IllegalStateException(e);}});
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->jdbc.queryForObject("select ?=any(pg_blocking_pids(?))",Boolean.class,pidA.get(),pidB));
            acquireAdvisory.countDown();var failure=producer.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("40P01",EventSequencingPostgresTest.sqlState(failure));antagonist.get(10,java.util.concurrent.TimeUnit.SECONDS);other.rollback();
        } finally {acquireAdvisory.countDown();}
        healthy();assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));healthy();
        System.out.println("STEP4E RECOVERY real PostgreSQL deadlock 40P01: victim rolled back, next position=1");
    }
    @Test void afterAssociationFaultAndDeferredConstraintRollbackThroughRealOwner() {
        reset(true);
        var sequencer=new JdbcEventTransactionSequencer((JpaTransactionManager)manager);
        var failing=new PrimaryEventTransactionRunner((JpaTransactionManager)manager,batch->{sequencer.execute(batch);throw new IllegalStateException("after associations");});
        var writer=new JpaBusinessEventOutboxAdapter(emf,new com.fasterxml.jackson.databind.ObjectMapper());
        var service=new AppendBusinessEventService(writer,failing,failing::recordPublishedEvent);
        assertThrows(IllegalStateException.class,()->service.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));
        healthy();assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
        // Omitted reference under the actual owner reaches PostgreSQL's deferred constraint.
        assertThrows(RuntimeException.class,()->protocol.runner().runInTransaction(()->{writer.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));return null;}));
        healthy();assertEquals(0,jdbc.queryForObject("select count(*) from business_event_outbox",Integer.class));
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));healthy();
    }
    @Test void preparedAndActiveCapacityCharacterization() throws Exception {
        int samples=Integer.getInteger("step4e.samples",6);
        assertTrue(samples>=6,"at least six samples per worker for this characterization");
        for(boolean active:List.of(false,true)) for(int workers:List.of(1,4,8))
            for(String kind:List.of("BUSINESS","BATCH4","COMMAND","REGISTRATION","COMMAND_REGISTRATION","LONG","ROLLBACK")) {
                reset(active);for(int i=0;i<3;i++)workload(kind,i); // Explicit warmup, excluded from measurements.
                jdbc.execute("truncate step4e_e_timing");commandTeNanos.clear();
                long[] before=counts();String lsn=jdbc.queryForObject("select pg_current_wal_lsn()::text",String.class);
                var suffix=metrics.find("pocoma.event.sequencing.suffix").timer();long suffixCount=suffix==null?0:suffix.count();
                double suffixNanos=suffix==null?0:suffix.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS);
                var latencies=new java.util.concurrent.CopyOnWriteArrayList<Long>();
                var start=new java.util.concurrent.CyclicBarrier(workers+1);
                var running=new java.util.concurrent.atomic.AtomicBoolean(true);
                var maxWaiters=new java.util.concurrent.atomic.AtomicInteger();
                var sampledLocks=new java.util.concurrent.atomic.AtomicLong();
                var maxWaitMs=new java.util.concurrent.atomic.DoubleAccumulator(Math::max,0);
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(workers+1)) {
                    var observer=pool.submit(()->{while(running.get()) {
                        var snapshot=jdbc.queryForMap("select count(*) n, coalesce(max(extract(epoch from (clock_timestamp()-query_start))*1000),0) age from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%reserve_event_positions%'");
                        int waits=((Number)snapshot.get("n")).intValue();
                        maxWaiters.accumulateAndGet(waits,Math::max);sampledLocks.addAndGet(waits);
                        maxWaitMs.accumulate(((Number)snapshot.get("age")).doubleValue());
                        java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
                    }});
                    var futures=new ArrayList<java.util.concurrent.Future<?>>();
                    for(int w=0;w<workers;w++) {int worker=w;futures.add(pool.submit(()->{
                        try {start.await(10,java.util.concurrent.TimeUnit.SECONDS);
                            for(int i=0;i<samples;i++){long t=System.nanoTime();workload(kind,worker+i);latencies.add(System.nanoTime()-t);}
                        } catch(Exception e){throw new IllegalStateException(e);}
                    }));}
                    long t=System.nanoTime();start.await(10,java.util.concurrent.TimeUnit.SECONDS);
                    try {for(var f:futures)f.get(90,java.util.concurrent.TimeUnit.SECONDS);}
                    finally {running.set(false);}
                    observer.get(10,java.util.concurrent.TimeUnit.SECONDS);double seconds=(System.nanoTime()-t)/1e9;
                    long[] after=counts();var rates=new ArrayList<Double>();for(int i=0;i<4;i++)rates.add((after[i]-before[i])/seconds);
                    double avgSuffix=suffix==null||suffix.count()==suffixCount?0:(suffix.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)-suffixNanos)/(suffix.count()-suffixCount)/1e6;
                    System.out.println("STEP4E CAPACITY "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.ofEntries(Map.entry("mode",active?"ACTIVE":"PREPARED"),Map.entry("workers",workers),Map.entry("workload",kind),
                        Map.entry("samples",latencies.size()),Map.entry("p50ms",quantile(latencies,.50)),Map.entry("p95ms",quantile(latencies,.95)),Map.entry("p99ms",quantile(latencies,.99)),
                        Map.entry("operationsPerSecond",latencies.size()/seconds),
                        Map.entry("commandTE",commandTeNanos.isEmpty()?Map.of():Map.of("samples",commandTeNanos.size(),"p50ms",quantile(new ArrayList<>(commandTeNanos),.50),"p95ms",quantile(new ArrayList<>(commandTeNanos),.95),"p99ms",quantile(new ArrayList<>(commandTeNanos),.99))),Map.entry("eventsPerSecond",rates),Map.entry("meanSuffixMs",avgSuffix),Map.entry("maxEWaiters",maxWaiters.get()),
                        Map.entry("sampledEWaiters",sampledLocks.get()),Map.entry("maxObservedEWaitMs",maxWaitMs.get()),
                        Map.entry("ownerTransactionsPerSecond",latencies.size()/seconds*(kind.equals("COMMAND")?4:kind.equals("REGISTRATION")?2:kind.equals("COMMAND_REGISTRATION")?3:1)),
                        Map.entry("intentionalRollbacks",kind.equals("ROLLBACK")?latencies.size():0),Map.entry("unexpectedDeadlocks",0),Map.entry("unexpectedTimeouts",0),
                        Map.entry("firstEToCommit",jdbc.queryForList("select percentile_cont(0.50) within group(order by ms) p50ms,percentile_cont(0.95) within group(order by ms) p95ms,max(ms) maxms from (select extract(epoch from (pg_xact_commit_timestamp(xid::text::xid)-min(acquired_at)))*1000 ms from step4e_e_timing group by xid) t")),
                        Map.entry("pgStats",jdbc.queryForList("select xact_commit,xact_rollback,deadlocks,blks_read,blks_hit,temp_bytes from pg_stat_database where datname=current_database()")),Map.entry("walBytes",jdbc.queryForObject("select pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)",Long.class,lsn)))));
                }
                if(active)healthy();else assertEquals(0,jdbc.queryForObject("select count(*) from event_position",Integer.class));
            }
    }
}
