package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
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

/** Actual ports, writers and application services. All ACTIVE changes are maintenance in this disposable DB. */
@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
    "pocoma.command-consumption.enabled=false", "pocoma.registration-consumption.enabled=false"})
@Import(RealProducerEventProtocolPostgresTest.Observation.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealProducerEventProtocolPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);
        r.add("spring.datasource.password",PG::getPassword);
    }
    static final List<EventFamily> FAMILIES = List.of(EventFamily.BUSINESS_EVENT, EventFamily.COMMAND_TERMINAL_EVENT,
            EventFamily.BINDING_FACT,EventFamily.USER_CREATED_FACT);
    static final List<String> SOURCES = List.of("business_event_outbox","command_terminal_events",
            "external_identity_binding_facts","user_created_facts");
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    @Autowired PrimaryEventProtocol protocol;
    @Autowired RecordingDeclarations declarations;
    @Autowired AppendBusinessEventService auxiliary;
    @Autowired JpaBusinessEventOutboxAdapter auxiliaryWriter;
    @MockitoSpyBean JpaPotCommandEventAppendAdapter business;
    @MockitoSpyBean JdbcCommandOutcomeAdapter terminals;
    @MockitoSpyBean JpaExternalIdentityBindingAdapter bindings;
    @MockitoSpyBean JdbcUserCreatedFactAdapter userFacts;
    @Autowired JpaExternalIdentityBindingFactAdapter bindingFacts;
    @Autowired JpaUserAuthorityAdapter users;
    @Autowired ExternalIdentityJdbcRepository identityRepository;
    @Autowired @Qualifier("commandConsumptionHandleFailure") HandleConsumptionFailureUseCase failure;
    @Autowired @Qualifier("commandConsumptionTerminalParticipant") com.kartaguez.pocoma.runtime.consumption.ConsumptionTerminalParticipant terminalParticipant;
    @Autowired JdbcRegistrationRequestStore requests;
    @Autowired ExecuteRegistrationService registration;
    @Autowired RecordedCommandInsertionPort commands;
    @Autowired CommandConsumptionExecution execution;
    @Autowired @Qualifier("commandConsumptionAcquire") AcquireConsumptionUseCase acquire;
    @Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute;
    final List<EventRef> accepted = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods=false) static class Observation {
        @Bean ExecuteRegistrationService realRegistration(RegistrationRequestReader requests, RegistrationOutcomeRepository outcomes,
                UserAuthorityPort users, ExternalIdentityBindingPort bindings, UserCreatedFactPort facts, PublishedEvents declarations) {
            return new ExecuteRegistrationService(requests,outcomes,users,bindings,facts,declarations);
        }

        @Bean @Primary RecordingDeclarations recordingDeclarations(@Qualifier("publishedEvents") PublishedEvents delegate) {
            return new RecordingDeclarations(delegate);
        }
    }
    static final class RecordingDeclarations implements PublishedEvents {
        final PublishedEvents delegate;
        final List<EventRef> refs = new CopyOnWriteArrayList<>();
        RecordingDeclarations(PublishedEvents delegate) {this.delegate=delegate;}
        @Override public void recordPublishedEvent(EventRef ref) {delegate.recordPublishedEvent(ref);refs.add(ref);}
    }
    @BeforeEach void clean() {
        jdbc.update("update event_sequencing_protocol set mode='PREPARED'");
        jdbc.execute("truncate table users, external_identity_binding_streams, external_identity_binding_occurrences, external_identities, recorded_commands, registration_requests, consumption_slots, "
                + "business_event_outbox, command_terminal_events, external_identity_binding_facts, user_created_facts cascade");
        jdbc.update("update event_sequence_authorities set last_position=0");
        jdbc.execute("create table if not exists step4d_trace(ord bigserial, phase text, pid int, xid bigint)");
        jdbc.execute("truncate step4d_trace");
        jdbc.execute("create or replace function step4d_trace_effect() returns trigger language plpgsql as $$ begin "
                + "insert into step4d_trace(phase,pid,xid) values(TG_TABLE_NAME||':'||TG_OP,pg_backend_pid(),txid_current()); "
                + "return new; end $$");
        for(String table: List.of("pot_global_versions","pot_headers","pot_version_metadata","users","external_identity_binding_streams","external_identity_binding_occurrences","external_identities","external_identity_binding_facts","user_created_facts",
                "business_event_outbox","command_outcomes","command_terminal_events","registration_outcomes",
                "consumption_slots","consumption_claims","event_sequence_authorities","business_event_positions",
                "command_terminal_event_positions","binding_fact_positions","user_created_fact_positions")) {
            jdbc.execute("drop trigger if exists step4d_trace_effect on "+table);
            jdbc.execute("create trigger step4d_trace_effect after insert or update on "+table
                    + " for each row execute function step4d_trace_effect()");
        }
        accepted.clear(); declarations.refs.clear();
        doAnswer(inv -> {var value=(BusinessEventsAccepted)inv.callRealMethod();accepted.addAll(value.events());return value;})
                .when(org.springframework.test.util.AopTestUtils.<JpaPotCommandEventAppendAdapter>getUltimateTargetObject(business)).appendAll(anyList());
        doAnswer(inv -> {var value=(EventRef)inv.callRealMethod();accepted.add(value);return value;})
                .when(org.springframework.test.util.AopTestUtils.<JdbcCommandOutcomeAdapter>getUltimateTargetObject(terminals)).publish(any());
        doAnswer(inv -> {var value=(EventRef)inv.callRealMethod();accepted.add(value);return value;})
                .when(userFacts).append(any(),any());
        doAnswer(inv -> {var value=(BindingAcquisitionAccepted)inv.callRealMethod();value.fact().ifPresent(accepted::add);return value;})
                .when(org.springframework.test.util.AopTestUtils.<JpaExternalIdentityBindingAdapter>getUltimateTargetObject(bindings)).acquireWithInitializer(any(),any(),any());
        doAnswer(inv -> {var value=(BindingDetachmentAccepted)inv.callRealMethod();value.fact().ifPresent(accepted::add);return value;})
                .when(org.springframework.test.util.AopTestUtils.<JpaExternalIdentityBindingAdapter>getUltimateTargetObject(bindings)).detach(any(),any());
    }
    void active(boolean active) {jdbc.update("update event_sequencing_protocol set mode=?",active?"ACTIVE":"PREPARED");}
    PrimaryEventTransactionRunner owner(){return protocol.runner();}
    int count(String table){return jdbc.queryForObject("select count(*) from "+table,Integer.class);}
    void declare(EventRef ref){declarations.recordPublishedEvent(ref);}
    UUID request(ExternalIdentity identity) {
        UUID id=UUID.randomUUID();owner().runInTransaction(()->{requests.insert(new RegistrationRequest(id,identity,"{}",Instant.now()));return null;});return id;
    }
    void exact(boolean active) {
        assertEquals(accepted,declarations.refs,"accepted identities and application declaration order");
        for(int i=0;i<FAMILIES.size();i++) {
            var family=FAMILIES.get(i);
            var expected=accepted.stream().filter(ref->ref.family()==family).map(EventRef::eventId).toList();
            String id=i==0?"id":"event_id";
            var persisted=jdbc.queryForList("select "+id+" from "+SOURCES.get(i),UUID.class);
            assertEquals(new HashSet<>(expected),new HashSet<>(persisted));assertEquals(expected.size(),persisted.size());
            var positioned=jdbc.queryForList("select event_id from event_position where family=? order by position",UUID.class,family.name());
            assertEquals(active?expected:List.of(),positioned);
            assertEquals(active?java.util.stream.LongStream.rangeClosed(1,expected.size()).boxed().toList():List.of(),
                    jdbc.queryForList("select position from event_position where family=? order by position",Long.class,family.name()),
                    "dense positions for exactly the accepted family publications");
            assertEquals(active?(long)expected.size():0L,jdbc.queryForObject("select last_position from event_sequence_authorities where family=?",Long.class,family.name()));
        }
    }
    void onePhysicalTransaction() {
        assertEquals(1,jdbc.queryForObject("select count(distinct (pid,xid)) from step4d_trace",Integer.class));
        assertSame(emf,((JpaTransactionManager)manager).getEntityManagerFactory());
        owner().runInTransaction(()-> {
            var em=EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
            Object[] jpa=(Object[])em.createNativeQuery("select pg_backend_pid(),txid_current()").getSingleResult();
            assertEquals(((Number)jpa[0]).intValue(),jdbc.queryForObject("select pg_backend_pid()",Integer.class));
            assertEquals(((Number)jpa[1]).longValue(),jdbc.queryForObject("select txid_current()",Long.class));return null;
        });
        System.out.println("STEP4D PHYSICAL "+jdbc.queryForList("select * from step4d_trace order by ord"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realRegistrationWinningExistingAndRejected(boolean active) {
        active(active);var identity=new ExternalIdentity("step4d","registration");UUID id=request(identity);
        owner().runInTransaction(()->registration.execute(id));
        assertEquals(List.of(EventFamily.USER_CREATED_FACT,EventFamily.BINDING_FACT),declarations.refs.stream().map(EventRef::family).toList());
        exact(active);onePhysicalTransaction();
        owner().runInTransaction(()->registration.execute(id));exact(active);
        UUID losing=request(identity);owner().runInTransaction(()->registration.execute(losing));exact(active);
        assertEquals(1,count("users"));assertEquals(2,count("registration_outcomes"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCommandAppliedBusinessAndTerminalAfterClaim(boolean active) {
        active(active);var identity=new ExternalIdentity("step4d","command");var user=new PocomaUserId(UUID.randomUUID());
        BindingId binding=owner().runInTransaction(()->{users.create(new User(user));var value=bindings.acquire(identity,user);value.fact().ifPresent(this::declare);return value.result().bindingId();});
        // Start a fresh command proof: retained Binding fact belongs to its earlier owner.
        jdbc.execute("truncate table external_identity_binding_facts, binding_fact_positions cascade");
        jdbc.update("update event_sequence_authorities set last_position=0 where family='BINDING_FACT'");
        accepted.clear();declarations.refs.clear();
        var command=new RecordedCommand(new CommandId(UUID.randomUUID()),new CommandType("POT_CREATE_V1"),
                "{\"label\":\"4D\",\"creatorId\":\""+user.value()+"\"}",Instant.now(),
                new TargetCommandEnvelope(identity,binding,new CommandAuthenticationEvidence(Set.of("pocoma:pot:create"),Instant.now().plusSeconds(60))));
        owner().runInTransaction(()->commands.insert(command));
        var claim=((AcquireResult.Acquired)acquire.acquire(new AcquireConsumptionInput(CommandConsumptionKeys.forCommand(command.commandId()),new WorkerId("4d"),new ClaimLease(Duration.ofSeconds(30))))).claim();
        jdbc.execute("truncate step4d_trace");
        execute.execute(new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),execution.forCommand(command.commandId())));
        assertEquals(List.of(EventFamily.BUSINESS_EVENT,EventFamily.COMMAND_TERMINAL_EVENT),accepted.stream().map(EventRef::family).toList());
        exact(active);onePhysicalTransaction();
        var phases=jdbc.queryForList("select phase from step4d_trace order by ord",String.class);
        assertTrue(phases.contains("consumption_claims:UPDATE"));
        if(active)assertTrue(phases.indexOf("consumption_claims:UPDATE")<phases.indexOf("event_sequence_authorities:UPDATE"));
        assertEquals("APPLIED",jdbc.queryForObject("select outcome_type from command_outcomes",String.class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void multipleRealBusinessAppendsAndAuxiliaryOwnership(boolean active) {
        active(active);
        owner().runInTransaction(()->{
            var batch=business.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1),new PotDeletedEvent(PotId.of(UUID.randomUUID()),2)));
            batch.events().forEach(this::declare);
            UUID id=auxiliary.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));accepted.add(new EventRef(EventFamily.BUSINESS_EVENT,id));
            assertEquals(0,count("business_event_positions"));return null;
        });
        exact(active);onePhysicalTransaction();
        UUID standalone=auxiliary.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));accepted.add(new EventRef(EventFamily.BUSINESS_EVENT,standalone));exact(active);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bindingAcquireDetachNoopsAndNewRepositoryAuxiliary(boolean active) {
        active(active);var identity=new ExternalIdentity("step4d","binding");var user=new PocomaUserId(UUID.randomUUID());
        owner().runInTransaction(()->{
            users.create(new User(user));var acquired=bindings.acquire(identity,user);acquired.fact().ifPresent(this::declare);
            assertTrue(bindings.acquire(identity,user).fact().isEmpty());
            var detached=bindings.detach(identity,acquired.result().bindingId());detached.fact().ifPresent(this::declare);
            assertTrue(bindings.detach(identity,acquired.result().bindingId()).fact().isEmpty());
            // Explicit auxiliary caller: publish only after its actual winning B mutation.
            var streams=new ExternalIdentityBindingStreamJdbcRepository(jdbc);
            assertEquals(2L,streams.lock(identity.issuer(),identity.subject()).currentRevision());
            var auxiliaryBinding=new BindingId(UUID.randomUUID());
            assertTrue(new ExternalIdentityBindingOccurrenceJdbcRepository(jdbc).reserve(auxiliaryBinding.value(),
                    identity.issuer(),identity.subject(),user.value(),3));
            assertTrue(identityRepository.acquire(identity.issuer(),identity.subject(),user.value(),auxiliaryBinding.value()));
            streams.advance(identity.issuer(),identity.subject(),2,3);
            UUID id=UUID.randomUUID();var fact=new ExternalIdentityAttached(id,identity,user,
                    auxiliaryBinding,new BindingRevision(3),Instant.now());
            EventRef ref=bindingFacts.append(fact);accepted.add(ref);declare(ref);return null;
        });exact(active);onePhysicalTransaction();
    }
    @Test void eachWriterCaughtJavaErrorIrreversiblyPoisonsPhysicalOwner() {
        var directBusiness=new JpaPotCommandEventAppendAdapter(emf,new com.fasterxml.jackson.databind.ObjectMapper());
        var directAux=new JpaBusinessEventOutboxAdapter(emf,new com.fasterxml.jackson.databind.ObjectMapper());
        var directTerminal=new JdbcCommandOutcomeAdapter(jdbc);
        var directUser=new JdbcUserCreatedFactAdapter(jdbc);
        var directFacts=new ExternalIdentityBindingFactJdbcRepository(jdbc);
        var directBinding=new JpaExternalIdentityBindingAdapter(identityRepository,jdbc);
        var directOutcome=new JdbcRegistrationOutcomeStore(jdbc);
        var directFactPort=new JpaExternalIdentityBindingFactAdapter(directFacts);
        for(Runnable bad:List.<Runnable>of(()->directBusiness.appendAll(null),()->directAux.append(null),
                ()->directTerminal.publish(null),()->directUser.append(UUID.randomUUID(),null),
                ()->directFacts.append(null),()->directFactPort.append(null),
                ()->directOutcome.insert(null), ()->directBinding.detach(null,null),
                ()->directBinding.acquireWithInitializer(new ExternalIdentity("step4d","poison"),new PocomaUserId(UUID.randomUUID()),()->{throw new IllegalStateException("initializer");}))) {
            assertThrows(IllegalTransactionStateException.class,bad::run, "direct new writer refuses a missing owner before input validation");
            assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
                users.create(new User(new PocomaUserId(UUID.randomUUID())));
                assertThrows(RuntimeException.class,bad::run);return null;
            }));assertEquals(0,count("users"));assertEquals(0,count("external_identity_binding_streams"));
            assertEquals(0,count("step4d_trace"));
            var closedOwner=new PrimaryEventTransactionRunner((JpaTransactionManager)manager,batch->{
                assertThrows(IllegalTransactionStateException.class,bad::run,"every direct writer refuses CLOSED before input validation");
            });
            assertThrows(RuntimeException.class,()->closedOwner.runInTransaction(()->{
                var value=directBusiness.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));
                value.events().forEach(closedOwner::recordPublishedEvent);return null;
            }));
            assertEquals(0,count("business_event_outbox"));assertEquals(0,count("step4d_trace"));
        }
        // A subsequent transaction on the same thread has a fresh OPEN context.
        auxiliary.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));assertEquals(1,count("business_event_outbox"));
    }
    @Test void terminalJavaFailureAfterFirstInsertCannotCommitWhenCaught() {
        var identity=new ExternalIdentity("step4d","terminal");var user=new PocomaUserId(UUID.randomUUID());
        UUID request=request(identity);
        owner().runInTransaction(()->registration.execute(request));
        var command=new RecordedCommand(new CommandId(UUID.randomUUID()),new CommandType("TEST"),"{}",Instant.now(),
                new TargetCommandEnvelope(identity,jdbc.queryForObject("select binding_id from external_identities",(rs,row)->new BindingId(rs.getObject(1,UUID.class))),new CommandAuthenticationEvidence(Set.of(),Instant.now().plusSeconds(60))));
        owner().runInTransaction(()->commands.insert(command));
        JdbcTemplate failing=spy(new JdbcTemplate(jdbc.getDataSource()));
        doAnswer(inv->{throw new IllegalStateException("after outcome, before terminal INSERT");}).when(failing)
                .update(startsWith("insert into command_terminal_events"),any(Object[].class));
        var writer=new JdbcCommandOutcomeAdapter(failing);
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
            assertThrows(IllegalStateException.class,()->writer.publish(new CommandOutcome.Rejected(command.commandId(),"TEST",Instant.now())));
            assertEquals(1,count("command_outcomes"));return null;
        }));assertEquals(0,count("command_outcomes"));assertEquals(0,count("command_terminal_events"));
    }
    @Test void ownerMismatchAndUnknownAmbientCannotCommitEvenWhenCaught() {
        assertThrows(IllegalTransactionStateException.class,()->new JdbcUserCreatedFactAdapter(jdbc).append(UUID.randomUUID(),new PocomaUserId(UUID.randomUUID())));
        assertThrows(UnexpectedRollbackException.class,()->new TransactionTemplate(manager).execute(status->{
            users.create(new User(new PocomaUserId(UUID.randomUUID())));
            assertThrows(IllegalTransactionStateException.class,()->new JdbcUserCreatedFactAdapter(jdbc).append(UUID.randomUUID(),new PocomaUserId(UUID.randomUUID())));return null;
        }));assertEquals(0,count("users"));
        var wrong=new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
            users.create(new User(new PocomaUserId(UUID.randomUUID())));
            assertThrows(IllegalTransactionStateException.class,()->new JdbcUserCreatedFactAdapter(new JdbcTemplate(wrong)).append(UUID.randomUUID(),new PocomaUserId(UUID.randomUUID())));return null;
        }));assertEquals(0,count("users"));
    }
    @Test void realWriterDuplicateLateOmittedAndFictitiousReferences() {
        active(true);
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
            var value=business.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));
            declare(value.events().getFirst());assertThrows(RuntimeException.class,()->declare(value.events().getFirst()));return null;
        }));assertEquals(0,count("business_event_outbox"));
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{auxiliaryWriter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));return null;}));
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{declare(new EventRef(EventFamily.BUSINESS_EVENT,UUID.randomUUID()));return null;}));
        var late=new PrimaryEventTransactionRunner((JpaTransactionManager)manager,batch->{assertThrows(RuntimeException.class,()->auxiliaryWriter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));});
        assertThrows(RuntimeException.class,()->late.runInTransaction(()->{var value=business.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));value.events().forEach(late::recordPublishedEvent);return null;}));
        assertEquals(0,count("business_event_outbox"));assertEquals(0,count("event_position"));
        assertEquals(0L,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
    }

    RecordedCommand seededCommand(boolean expired) {
        var identity=new ExternalIdentity("step4d","seed-"+UUID.randomUUID());UUID id=request(identity);
        var registered=(RegistrationOutcome.Registered)owner().runInTransaction(()->registration.execute(id));
        var command=new RecordedCommand(new CommandId(UUID.randomUUID()),new CommandType("POT_CREATE_V1"),
                "{\"label\":\"4D\",\"creatorId\":\""+registered.userId().value()+"\"}",Instant.now(),
                new TargetCommandEnvelope(identity,registered.bindingId(),new CommandAuthenticationEvidence(Set.of("pocoma:pot:create"),
                        Instant.now().plusSeconds(expired?-1:60))));
        owner().runInTransaction(()->commands.insert(command));return command;
    }
    Claim claim(RecordedCommand command) {
        return ((AcquireResult.Acquired)acquire.acquire(new AcquireConsumptionInput(CommandConsumptionKeys.forCommand(command.commandId()),
                new WorkerId("4d"),new ClaimLease(Duration.ofSeconds(30))))).claim();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void realCommandRejectedAndThFailedDeclareOnlyTheirTerminals(boolean active) {
        active(active);var rejected=seededCommand(true);var first=claim(rejected);
        execute.execute(new ExecuteConsumptionInput(first.slotId(),first.claimId(),execution.forCommand(rejected.commandId())));
        assertEquals(0,count("business_event_outbox"));assertEquals("REJECTED",jdbc.queryForObject("select outcome_type from command_outcomes",String.class));exact(active);
        var failed=seededCommand(false);var second=claim(failed);
        var classified=new com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionTechnicalFailureClassifier(Clock.systemUTC())
                .classify(new NullPointerException("controlled terminal failure after rollback"));
        failure.handle(new HandleConsumptionFailureInput(second.slotId(),second.claimId(),classified,execution.terminalFailureEffect(failed.commandId())));
        assertEquals("FAILED",jdbc.queryForObject("select outcome_type from command_outcomes where command_id=?",String.class,failed.commandId().value()));
        assertEquals(0,count("business_event_outbox"));exact(active);
    }
    @Test void failureAfterRealSequencingRestoresCommandSlotClaimAndEveryEffect() {
        active(true);var command=seededCommand(false);var claim=claim(command);
        var protectedTables=List.of("pot_global_versions","pot_headers","pot_version_metadata","consumption_inputs","consumption_results");
        var beforeEffects=new HashMap<String,Integer>();protectedTables.forEach(table->beforeEffects.put(table,count(table)));
        var primary=(JpaTransactionManager)manager;
        var failingOwner=new PrimaryEventTransactionRunner(primary,batch->{
            new JdbcEventTransactionSequencer(primary).execute(batch);
            assertEquals(1,count("business_event_positions"));assertEquals(1,count("command_terminal_event_positions"));
            assertEquals("DONE",jdbc.queryForObject("select status from consumption_slots where slot_id=?",String.class,claim.slotId()));
            throw new IllegalStateException("after real associations and S/Claim");
        });
        var transactional=new com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase(
                new com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService(terminalParticipant.finalizer()),
                new com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner(failingOwner,true));
        var failed=assertThrows(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.class,
                ()->transactional.execute(new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),execution.forCommand(command.commandId()))));
        assertEquals(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.ROLLED_BACK,failed.outcome());
        assertEquals(0,count("business_event_outbox"));assertEquals(0,count("command_terminal_events"));assertEquals(0,count("command_outcomes"));
        assertEquals(0,count("business_event_positions"));assertEquals(0,count("command_terminal_event_positions"));
        protectedTables.forEach(table->assertEquals(beforeEffects.get(table).intValue(),count(table),table+" physically restored"));
        assertEquals("PENDING",jdbc.queryForObject("select status from consumption_slots where slot_id=?",String.class,claim.slotId()));
        assertNull(jdbc.queryForObject("select ended_at from consumption_claims where claim_id=?",java.sql.Timestamp.class,claim.claimId().value()));
        assertEquals(0L,jdbc.queryForObject("select sum(last_position) from event_sequence_authorities where family in ('BUSINESS_EVENT','COMMAND_TERMINAL_EVENT')",Long.class));
    }
    @Test void incompatibleManagerOrEmfCompositionIsRefusedBeforeAnyEffect() {
        var differentEmf=mock(EntityManagerFactory.class);
        assertThrows(IllegalArgumentException.class,()->new PrimaryEventProtocol(manager,differentEmf,jdbc));
        var differentManager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        assertThrows(IllegalArgumentException.class,()->new PrimaryEventProtocol(differentManager,emf,jdbc));
        assertEquals(0,count("users"));
    }

    @Test void unsupportedBusinessMappingPoisonsOwnerAndPersistsNothing() {
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
            users.create(new User(new PocomaUserId(UUID.randomUUID())));
            assertThrows(com.kartaguez.pocoma.engine.consume.command.execution.CommandExecutionInvariantViolationException.class,
                    ()->business.appendAll(List.of(new ForeignEvent())));return null;
        }));assertEquals(0,count("users"));assertEquals(0,count("business_event_outbox"));
    }
    private record ForeignEvent() implements com.kartaguez.pocoma.domain.event.BusinessEvent {
        @Override public com.kartaguez.pocoma.domain.event.EventType eventType(){return new com.kartaguez.pocoma.domain.event.EventType("FOREIGN");}
    }
    @Test void preparedOmissionAndFictitiousAreDetectedByOracleWithoutClaimingSqlCompleteness() {
        UUID id=owner().runInTransaction(()->auxiliaryWriter.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));
        assertEquals(List.of(id),jdbc.queryForList("select id from business_event_outbox",UUID.class));
        assertTrue(declarations.refs.isEmpty()); // PREPARED deliberately permits this adversarial omission.
        assertNotEquals(Set.of(id),declarations.refs.stream().map(EventRef::eventId).collect(java.util.stream.Collectors.toSet()));
        UUID fictitious=UUID.randomUUID();owner().runInTransaction(()->{declare(new EventRef(EventFamily.BUSINESS_EVENT,fictitious));return null;});
        assertNotEquals(Set.of(id),declarations.refs.stream().map(EventRef::eventId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0,count("event_position"));
    }
    @Test void actualBusinessCompositionRunsUnderRestrictedRuntimeRole() {
        active(true);
        jdbc.execute("grant select,insert on business_event_outbox to pocoma_event_runtime");
        jdbc.execute("grant insert on step4d_trace to pocoma_event_runtime");
        jdbc.execute("grant usage on sequence step4d_trace_ord_seq to pocoma_event_runtime");
        owner().runInTransaction(()->{
            jdbc.execute("set local role pocoma_event_runtime");
            var value=business.appendAll(List.of(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1)));
            value.events().forEach(this::declare);return null;
        });exact(true);
        assertEquals(1,count("business_event_positions"));
    }

    @Test void realTaAndThRetryHaveNoPublicationAndNeverWaitForLockedE() throws Exception {
        active(true);var command=seededCommand(false);var before=List.copyOf(declarations.refs);
        try(var blocker=jdbc.getDataSource().getConnection();var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);blocker.createStatement().execute("lock event_sequence_authorities in access exclusive mode");
            var future=pool.submit(()->{
                var claim=claim(command);
                var classified=new com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionTechnicalFailureClassifier(Clock.systemUTC())
                        .classify(new IllegalStateException("controlled retry", new java.sql.SQLTransientConnectionException("controlled retry", "08006")));
                assertEquals("TRANSIENT",classified.category());
                failure.handle(new HandleConsumptionFailureInput(claim.slotId(),claim.claimId(),classified,execution.terminalFailureEffect(command.commandId())));
            });
            try {future.get(5,java.util.concurrent.TimeUnit.SECONDS);}
            finally {blocker.rollback();}
        }
        assertEquals(before,declarations.refs);assertEquals(0,count("command_outcomes"));assertEquals(0,count("command_terminal_events"));exact(true);
    }

    @ParameterizedTest @ValueSource(strings={"TERMINAL","USER_CREATED","BINDING_FACT","REGISTRATION_OUTCOME"})
    void eachJdbcWriterCaughtJavaFailureAfterSuccessfulInsertRollsBackPhysically(String kind) {
        var identity=new ExternalIdentity("step4d","post-insert");
        UUID requestId=request(identity);var user=new PocomaUserId(UUID.randomUUID());
        owner().runInTransaction(()->{users.create(new User(user));return null;});
        JdbcTemplate failing=spy(new JdbcTemplate(jdbc.getDataSource()));
        String table=switch(kind){case "TERMINAL"->"command_outcomes";case "USER_CREATED"->"user_created_facts";
            case "BINDING_FACT"->"external_identity_binding_facts";default->"registration_outcomes";};
        doAnswer(inv->{inv.callRealMethod();throw new IllegalStateException("Java failure after successful INSERT into "+table);})
                .when(failing).update(startsWith("insert into "+table),any(Object[].class));
        Runnable write;
        if(kind.equals("TERMINAL")) {
            var command=seededCommand(false);
            write=()->new JdbcCommandOutcomeAdapter(failing).publish(new CommandOutcome.Rejected(command.commandId(),"TEST",Instant.now()));
        } else if(kind.equals("USER_CREATED")) {
            write=()->new JdbcUserCreatedFactAdapter(failing).append(requestId,user);
        } else if(kind.equals("REGISTRATION_OUTCOME")) {
            write=()->new JdbcRegistrationOutcomeStore(failing).insert(new RegistrationOutcome.Rejected(requestId));
        } else {
            var binding=owner().runInTransaction(()->{var value=bindings.acquire(identity,user);value.fact().ifPresent(this::declare);return value.result().bindingId();});
            var fact=new ExternalIdentityDetached(UUID.randomUUID(),identity,user,binding,new BindingRevision(2),Instant.now());
            write=()->new JpaExternalIdentityBindingFactAdapter(new ExternalIdentityBindingFactJdbcRepository(failing)).append(fact);
        }
        int before=count(table), usersBefore=count("users");
        assertThrows(RuntimeException.class,()->owner().runInTransaction(()->{
            users.create(new User(new PocomaUserId(UUID.randomUUID())));
            assertThrows(IllegalStateException.class,write::run);
            assertEquals(before+1,count(table),"INSERT succeeded and PostgreSQL transaction is still usable");
            System.out.println("STEP4D POISON "+kind+" provisional INSERT visible, xid="+jdbc.queryForObject("select txid_current()",Long.class));
            return null;
        }));
        assertEquals(before,count(table));assertEquals(usersBefore,count("users"));
    }
}
