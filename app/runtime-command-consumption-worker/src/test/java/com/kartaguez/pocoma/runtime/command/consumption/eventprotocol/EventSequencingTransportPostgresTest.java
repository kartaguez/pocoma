package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.consume.command.pot.publication.AppendBusinessEventService;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner;
import com.kartaguez.pocoma.runtime.publication.PrimaryEventProtocol;

/** Real transport failure, real owner/writer/suffix, fresh independent durable-state check. */
@SpringBootTest(classes=PocomaCommandConsumptionWorkerApplication.class,
    properties={"pocoma.command-consumption.enabled=false","spring.datasource.hikari.maximum-pool-size=4"})
@Testcontainers @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class EventSequencingTransportPostgresTest {
    @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
    static CommitAcknowledgementProxy proxy;
    static synchronized String url() {
        try {if(proxy==null)proxy=new CommitAcknowledgementProxy(PG.getHost(),PG.getMappedPort(5432));}
        catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        return "jdbc:postgresql://localhost:"+proxy.localPort()+"/"+PG.getDatabaseName()+"?sslmode=disable&preferQueryMode=simple&socketTimeout=10";
    }
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",EventSequencingTransportPostgresTest::url);
        r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired PrimaryEventProtocol protocol;
    @Autowired AppendBusinessEventService append;
    JdbcTemplate independent;
    @BeforeEach void clean() {
        independent=new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        independent.execute("truncate business_event_outbox,event_sequencing_cutovers cascade");
        independent.update("update event_sequence_authorities set last_position=0");
        independent.execute("update event_sequencing_protocol set mode='ACTIVE'");
    }
    @ParameterizedTest @EnumSource(value=CommitAcknowledgementProxy.Fault.class,names={"BEFORE_COMMIT","AFTER_COMMIT"})
    void cutoverTransportLossReconcilesSameOperation(CommitAcknowledgementProxy.Fault fault) throws Exception {
        independent.update("update event_sequencing_protocol set mode='PREPARED'");
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        UUID operation=UUID.randomUUID();
        try(var connection=new DriverManagerDataSource(url(),PG.getUsername(),PG.getPassword()).getConnection()) {
            connection.setAutoCommit(false);
            connection.createStatement().execute("set local lock_timeout='5s'");
            connection.createStatement().execute("set local statement_timeout='30s'");
            try(var statement=connection.prepareStatement("select activate_event_sequencing(?,'pocoma_event_runtime')")) {
                statement.setObject(1,operation);statement.execute();
            }
            proxy.armed.set(fault);assertThrows(java.sql.SQLException.class,connection::commit);
        }
        boolean committed=fault==CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
        assertEquals(committed?"ACTIVE":"PREPARED",independent.queryForObject("select mode from event_sequencing_protocol",String.class));
        assertEquals(committed?1:0,independent.queryForObject("select count(*) from event_sequencing_cutovers where operation_id=?",Integer.class,operation));
        // Same UUID replays rolled back cutover or reconciles committed cutover under continued freeze.
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        var transactional=new JdbcTemplate(source);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(s->{
            transactional.execute("set local lock_timeout='5s'");transactional.execute("set local statement_timeout='30s'");
            transactional.queryForObject("select activate_event_sequencing(?,'pocoma_event_runtime')::text",String.class,operation);
        });
        assertEquals(1,independent.queryForObject("select count(*) from event_sequencing_cutovers",Integer.class));
        assertEquals(1,independent.queryForObject("select count(*) from business_event_positions",Integer.class));
        System.out.println("STEP4E CUTOVER TRANSPORT "+fault+" durableCommit="+committed+" reconciled operation="+operation);
    }
    @AfterAll static void stopProxy() throws Exception {if(proxy!=null)proxy.close();}
    @ParameterizedTest @EnumSource(value=CommitAcknowledgementProxy.Fault.class,names={"BEFORE_COMMIT","AFTER_COMMIT"})
    void unknownCommitReconcilesDurableTruthWithoutBlindReplay(CommitAcknowledgementProxy.Fault fault) {
        var id=new AtomicReference<UUID>();int before=proxy.intercepted.get(),committed=proxy.committedReplies.get();
        var owner=new ConsumptionOwnerTransactionRunner(protocol.runner());proxy.armed.set(fault);
        var failure=assertThrows(ConsumptionTransactionResolutionException.class,()->owner.runInTransaction(()->{
            id.set(append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));return null;
        }));
        assertEquals(before+1,proxy.intercepted.get(),"COMMIT packet actually intercepted");
        long durable=fault==CommitAcknowledgementProxy.Fault.AFTER_COMMIT?1:0;
        assertEquals(durable,independent.queryForObject("select count(*) from business_event_outbox where id=?",Long.class,id.get()));
        assertEquals(durable,independent.queryForObject("select last_position from event_sequence_authorities where family='BUSINESS_EVENT'",Long.class));
        assertEquals(durable,independent.queryForObject("select count(*) from business_event_positions",Long.class));
        assertEquals(committed+(int)durable,proxy.committedReplies.get());
        System.out.println("STEP4E TRANSPORT OBSERVED "+fault+" reported="+failure.outcome()+" durable="+durable+" cause="+failure.getCause().getClass()+" serverCommittedReplies="+proxy.committedReplies.get());
        assertEquals(ConsumptionTransactionResolutionException.Outcome.UNKNOWN,failure.outcome());
        // Reconcile the stable UUID before retry decisions. No blind replay of UNKNOWN.
        append.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));
        assertEquals(durable+1,independent.queryForObject("select count(*) from business_event_positions",Long.class));
        assertEquals(durable+1,independent.queryForObject("select max(position) from business_event_positions",Long.class));
        System.out.println("STEP4E TRANSPORT "+fault+" application="+failure.outcome()+" durable="+durable+" committedReplies="+proxy.committedReplies.get());
    }
}
