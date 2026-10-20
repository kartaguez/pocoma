package com.kartaguez.pocoma.runtime.command.consumption.sequencing;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Qualifier;
import com.kartaguez.pocoma.contracts.command.*;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.consumption.claim.*;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.engine.consume.command.consumption.CommandConsumptionExecution;
import com.kartaguez.pocoma.engine.consume.command.pot.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.supra.consume.command.CommandConsumptionKeys;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

/** Isolated Step 3C experiment. Replaces the manager only in this test context. */
@SpringBootTest(classes={PocomaCommandConsumptionWorkerApplication.class, JpaTerminalSequencingPostgresTest.Experiment.class},
 properties={"pocoma.command-consumption.enabled=false","spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class JpaTerminalSequencingPostgresTest {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",PG::getJdbcUrl); r.add("spring.datasource.username",PG::getUsername); r.add("spring.datasource.password",PG::getPassword);
 }
 @TestConfiguration(proxyBeanMethods=false) static class Experiment {
  // Historical experiment: deliberately retains its pre-4D writer and manager semantics.
  // It is not a certified production composition and does not bypass any production guard.
  @Bean @Primary BusinessEventAppendPort historicalExperimentWriter(EntityManagerFactory emf, com.fasterxml.jackson.databind.ObjectMapper mapper) {
   return event -> {
    UUID id=UUID.randomUUID();
    var envelope=new com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.BusinessEventRecordMapper(mapper)
      .toEnvelope(new com.kartaguez.pocoma.domain.pot.event.RecordedEvent<>(id,event,java.time.Instant.now(),
        com.kartaguez.pocoma.domain.pot.event.EventTraceMetadata.of(null,null)));
    EntityManagerFactoryUtils.getTransactionalEntityManager(emf).persist(new JpaBusinessEventOutboxEntity(envelope));return id;
   };
  }

  @Bean @Primary TerminalManager transactionManager(EntityManagerFactory emf,DataSource ds){return new TerminalManager(emf,ds);}
 }
 static final Object KEY=new Object();
 static class Batch {
  final LinkedHashSet<UUID> ids=new LinkedHashSet<>(); final List<String> trace=new ArrayList<>();
  boolean closed,fail; int suffix; Runnable afterSuffix=()->{};
 }
 static Batch batch(){return (Batch)TransactionSynchronizationManager.getResource(KEY);}
 static class TerminalManager extends JpaTransactionManager {
  final EntityManagerFactory emf; final JdbcTemplate sql; Runnable beforeE=()->{};
  TerminalManager(EntityManagerFactory emf,DataSource ds){super(emf);this.emf=emf;setDataSource(ds);sql=new JdbcTemplate(ds);}
  @Override protected void doBegin(Object tx,TransactionDefinition d){super.doBegin(tx,d);TransactionSynchronizationManager.bindResource(KEY,new Batch());}
  @Override protected void prepareForCommit(DefaultTransactionStatus s){batch().trace.add("prepareForCommit");super.prepareForCommit(s);}
  @Override protected void doCommit(DefaultTransactionStatus s){
   Batch b=batch(); var em=EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
   em.flush(); b.trace.add("flush"); b.closed=true;
   if(!b.ids.isEmpty()){
    beforeE.run();b.suffix++;
    for(UUID id:b.ids){
     assertEquals(1,sql.queryForObject("select count(*) from business_event_outbox where id=?",Integer.class,id));
     var j=sql.queryForMap("select pg_backend_pid() pid,txid_current() xid");
     Object[] h=(Object[])em.createNativeQuery("select pg_backend_pid(),txid_current()").getSingleResult();
     assertEquals(((Number)j.get("pid")).longValue(),((Number)h[0]).longValue());
     assertEquals(((Number)j.get("xid")).longValue(),((Number)h[1]).longValue());
     long n=sql.queryForObject("update experiment_3c.authority set n=n+1 returning n",Long.class);
     b.trace.add("E:"+n+":"+j);sql.update("insert into experiment_3c.position values (?,?)",id,n);b.trace.add("position:"+id);
    }
    if(b.fail)throw new IllegalStateException("after E");
   }
   b.afterSuffix.run();super.doCommit(s);b.trace.add("physicalCommit");System.out.println("STEP3C "+b.trace);
  }
  @Override protected Object doSuspend(Object tx){Batch b=(Batch)TransactionSynchronizationManager.unbindResource(KEY);return new Object[]{super.doSuspend(tx),b};}
  @Override protected void doResume(Object tx,Object suspended){Object[] p=(Object[])suspended;super.doResume(tx,p[0]);TransactionSynchronizationManager.bindResource(KEY,p[1]);}
  @Override protected void doCleanupAfterCompletion(Object tx){TransactionSynchronizationManager.unbindResourceIfPossible(KEY);super.doCleanupAfterCompletion(tx);}
 }
 @Autowired TerminalManager manager;
 @Autowired @Qualifier("commandConsumptionTransactionRunner") TransactionRunner owner;
 @Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute;
 @Autowired CommandConsumptionExecution execution;
 @Autowired JpaConsumptionLifecycleAdapter lifecycle;
 @Autowired RecordedCommandInsertionPort commands;
 @Autowired EntityManagerFactory emf;
 @Autowired BusinessEventAppendPort writer;
 @Autowired JdbcTemplate sql;
 @Autowired DataSource ds;
 static boolean installed;
 @BeforeEach void setup(){
  sql.execute("create schema if not exists experiment_3c");
  sql.execute("create table if not exists experiment_3c.authority(n bigint not null)");
  sql.execute("create table if not exists experiment_3c.position(id uuid primary key references business_event_outbox(id),n bigint unique)");
  sql.execute("truncate experiment_3c.position,experiment_3c.authority");sql.update("insert into experiment_3c.authority values(0)");
  if(!installed){
   emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(EventListenerRegistry.class).appendListeners(EventType.POST_INSERT,new PostInsertEventListener(){
    public void onPostInsert(PostInsertEvent e){if(e.getEntity() instanceof JpaBusinessEventOutboxEntity entity){
     Batch b=Objects.requireNonNull(batch());if(b.closed)throw new IllegalStateException("late Event");b.ids.add(entity.id());b.trace.add("insert:"+entity.id());
    }}
    public boolean requiresPostCommitHandling(EntityPersister p){return false;}
   });installed=true;
  }
  sql.execute("create table if not exists experiment_3c.audit(ord bigserial, phase text, pid int, xid bigint)");
  sql.execute("""
   create or replace function experiment_3c.observe() returns trigger language plpgsql as $$
   begin insert into experiment_3c.audit(phase,pid,xid) values(TG_TABLE_NAME||':'||TG_OP,pg_backend_pid(),txid_current()); return NEW; end $$
   """);
  for(String table:List.of("business_event_outbox","experiment_3c.authority","experiment_3c.position")){
   sql.execute("drop trigger if exists step3c_observe on "+table);
   sql.execute("create trigger step3c_observe after insert or update on "+table+" for each row execute function experiment_3c.observe()");
  }
  sql.execute("truncate experiment_3c.audit");
  assertNull(batch());
 }
 void publish(){writer.append(new PotCreatedEvent(PotId.of(UUID.randomUUID()),1));}
 TransactionTemplate tx(int propagation){var t=new TransactionTemplate(manager);t.setPropagationBehavior(propagation);return t;}
 long count(){return sql.queryForObject("select count(*) from experiment_3c.position",Long.class);}
 @AfterEach void clean(){assertNull(batch());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());}
 @Test void aDeferredJpaInsertAtomicOnSharedConnection(){
  Batch[] seen=new Batch[1];tx(0).executeWithoutResult(s->{seen[0]=batch();publish();assertTrue(batch().ids.isEmpty(),"INSERT deferred");});
  assertEquals(1,seen[0].suffix);assertEquals(1,count());
  int e=java.util.stream.IntStream.range(0,seen[0].trace.size()).filter(i->seen[0].trace.get(i).startsWith("E:")).findFirst().orElseThrow();
  assertTrue(seen[0].trace.indexOf("flush")<e);
  assertEquals(List.of("business_event_outbox:INSERT","authority:UPDATE","position:INSERT"),sql.queryForList("select phase from experiment_3c.audit order by ord",String.class));
  assertEquals(1,sql.queryForObject("select count(distinct (pid,xid)) from experiment_3c.audit",Integer.class));
  System.out.println("STEP3C DATABASE "+sql.queryForList("select * from experiment_3c.audit order by ord"));
 }
 public static class Participant {
  Runnable action;
  @Transactional(propagation=Propagation.MANDATORY) public void mandatory(){action.run();}
  @Transactional public void required(){action.run();}
 }
 @SuppressWarnings("deprecation") Participant participant(Runnable action){
  Participant target=new Participant();target.action=action;var p=new ProxyFactory(target);
  p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return (Participant)p.getProxy();
 }
 @Test void bRequiredMandatoryAndAutonomousAnnotation(){
  Batch[] seen=new Batch[1];Participant p=participant(()->{if(seen[0]==null)seen[0]=batch();assertSame(seen[0],batch());publish();assertEquals(0,batch().suffix);});
  tx(0).executeWithoutResult(s->{p.required();p.mandatory();});assertEquals(1,seen[0].suffix);assertEquals(2,count());
  assertThrows(IllegalTransactionStateException.class,p::mandatory);seen[0]=null;p.required();assertEquals(3,count());
 }
 @Test void cNoEventNoEAndRollbackPropagates(){
  Batch[] empty=new Batch[1];tx(0).executeWithoutResult(s->{empty[0]=batch();assertEquals(0,sql.queryForObject("select count(*) from pg_locks where pid=pg_backend_pid() and relation='experiment_3c.authority'::regclass",Integer.class));});
  assertEquals(0,empty[0].suffix);assertEquals(0,count());
  long before=sql.queryForObject("select count(*) from business_event_outbox",Long.class);
  assertThrows(IllegalStateException.class,()->tx(0).executeWithoutResult(s->{publish();batch().fail=true;}));
  assertEquals(before,sql.queryForObject("select count(*) from business_event_outbox",Long.class));assertEquals(0,count());assertEquals(0,sql.queryForObject("select n from experiment_3c.authority",Long.class));
 }
 @Test void dCallbacksCanWriteAfterBeforeCommitButBeforeDoCommit(){
  Batch[] seen=new Batch[1];tx(0).executeWithoutResult(s->{seen[0]=batch();publish();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   public int getOrder(){return Integer.MAX_VALUE;}
   public void beforeCommit(boolean readOnly){batch().trace.add("beforeCommit");publish();}
   public void beforeCompletion(){batch().trace.add("beforeCompletion");publish();}
  });});
  assertEquals(3,count());assertEquals(1,seen[0].suffix);assertTrue(seen[0].trace.indexOf("prepareForCommit")<seen[0].trace.indexOf("beforeCommit"));assertTrue(seen[0].trace.indexOf("beforeCompletion")<seen[0].trace.indexOf("flush"));
 }
 @Test void eNestedRejectedButProgrammaticSavepointStillPossible(){
  tx(0).executeWithoutResult(s->{
   assertThrows(NestedTransactionNotSupportedException.class,()->tx(TransactionDefinition.PROPAGATION_NESTED).executeWithoutResult(n->publish()));
   assertThrows(NestedTransactionNotSupportedException.class,s::createSavepoint);
   try{
    var c=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
    var sp=c.setSavepoint();publish();EntityManagerFactoryUtils.getTransactionalEntityManager(emf).flush();assertEquals(1,batch().ids.size());
    c.rollback(sp);c.releaseSavepoint(sp);assertEquals(1,batch().ids.size(),"memory NOT restored by raw JDBC savepoint");s.setRollbackOnly();
   }catch(java.sql.SQLException e){throw new IllegalStateException(e);}
  });assertEquals(0,count());
 }
 @Test void fRequiresNewSuspendsCollectorAndOuterRollbackKeepsInnerCommit(){
  tx(0).executeWithoutResult(s->{Batch outer=batch();publish();tx(TransactionDefinition.PROPAGATION_REQUIRES_NEW).executeWithoutResult(n->{assertNotSame(outer,batch());publish();});assertSame(outer,batch());s.setRollbackOnly();});
  assertEquals(1,count());assertEquals(1,sql.queryForObject("select n from experiment_3c.authority",Long.class));
 }
 @Test void gHibernateCommitCanStillFlushAfterE(){
  tx(0).executeWithoutResult(s->{publish();batch().afterSuffix=()->{
   var em=EntityManagerFactoryUtils.getTransactionalEntityManager(emf);
   var entity=em.find(JpaBusinessEventOutboxEntity.class,batch().ids.iterator().next());
   try{var field=JpaBusinessEventOutboxEntity.class.getDeclaredField("attemptCount");field.setAccessible(true);field.set(entity,7);}
   catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
  };});
  assertEquals(List.of("business_event_outbox:INSERT","authority:UPDATE","position:INSERT","business_event_outbox:UPDATE"),sql.queryForList("select phase from experiment_3c.audit order by ord",String.class));
  System.out.println("STEP3C POST-E COUNTEREXAMPLE "+sql.queryForList("select * from experiment_3c.audit order by ord"));
 }
 @Test void iLateJpaEventRejectedAndEntireTransactionRollsBack(){
  long before=sql.queryForObject("select count(*) from business_event_outbox",Long.class);
  assertThrows(RuntimeException.class,()->tx(0).executeWithoutResult(s->{publish();batch().afterSuffix=this::publish;}));
  assertEquals(before,sql.queryForObject("select count(*) from business_event_outbox",Long.class));assertEquals(0,count());
  assertEquals(0,sql.queryForObject("select n from experiment_3c.authority",Long.class));
 }
 @Test void jCollectorIsCleanOnReusedWorkerThread() throws Exception {
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{
   Batch first=pool.submit(()->{assertNull(batch());Batch[] b=new Batch[1];tx(0).executeWithoutResult(s->{b[0]=batch();publish();});assertNull(batch());return b[0];}).get(15,java.util.concurrent.TimeUnit.SECONDS);
   Batch second=pool.submit(()->{assertNull(batch());Batch[] b=new Batch[1];tx(0).executeWithoutResult(s->{b[0]=batch();publish();});assertNull(batch());return b[0];}).get(15,java.util.concurrent.TimeUnit.SECONDS);
   assertNotSame(first,second);assertEquals(2,count());assertNull(batch());
  }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS));}
 }
 @Test void hRealCommandFinalizationAndClaimClosurePrecedeE(){
  UUID user=UUID.randomUUID(), binding=UUID.randomUUID();
  var identity=new ExternalIdentity("step3c","subject-"+UUID.randomUUID());
  sql.update("insert into users(user_id) values (?)",user);
  sql.update("insert into external_identity_binding_streams(issuer,subject,current_revision) values (?,?,0)",identity.issuer(),identity.subject());
  sql.update("insert into external_identity_binding_occurrences(binding_id,issuer,subject,user_id,attached_revision,created_at) values (?,?,?,?,0,now())",binding,identity.issuer(),identity.subject(),user);
  sql.update("insert into external_identities(issuer,subject,user_id,binding_id) values (?,?,?,?)",identity.issuer(),identity.subject(),user,binding);
  var command=new RecordedCommand(new CommandId(UUID.randomUUID()),PotCommandTypes.POT_CREATE_V1,
   "{\"label\":\"step3c\",\"creatorId\":\"%s\"}".formatted(user),Instant.now(),
   new TargetCommandEnvelope(identity,new BindingId(binding),new CommandAuthenticationEvidence(Set.of("pocoma:pot:create"),Instant.now().plusSeconds(600))));
  owner.runInTransaction(()->commands.insert(command));
  var claim=assertInstanceOf(AcquireResult.Acquired.class,owner.runInTransaction(()->lifecycle.acquire(
   CommandConsumptionKeys.forCommand(command.commandId()),ClaimId.generate(),new WorkerId("step3c"),new ClaimLease(Duration.ofSeconds(30)),Instant.now()))).claim();
  List<Batch> observed=new ArrayList<>();
  manager.beforeE=()->{
   assertEquals(1,sql.queryForObject("select count(*) from consumption_slots where slot_id=? and status='DONE'",Integer.class,claim.slotId()));
   assertEquals(1,sql.queryForObject("select count(*) from consumption_claims where claim_id=? and ended_at is not null",Integer.class,claim.claimId().value()));
   assertEquals(1,sql.queryForObject("select count(*) from command_terminal_events where command_id=?",Integer.class,command.commandId().value()));
   batch().trace.add("verified:S-DONE/claim-closed/terminal-event");observed.add(batch());
  };
  try{execute.execute(new ExecuteConsumptionInput(claim.slotId(),claim.claimId(),execution.forCommand(command.commandId())));}
  finally{manager.beforeE=()->{};}
  assertEquals(1,observed.size());assertEquals(1,observed.getFirst().suffix);assertEquals(1,count());
  assertEquals(List.of("business_event_outbox:INSERT","authority:UPDATE","position:INSERT"),sql.queryForList("select phase from experiment_3c.audit order by ord",String.class));
 }
}

/** Observe Boot's real manager without the experimental configuration. */
@SpringBootTest(classes=PocomaCommandConsumptionWorkerApplication.class,properties={"pocoma.command-consumption.enabled=false"})
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CurrentTransactionManagerPostgresTest {
 @Container static final PostgreSQLContainer PG=new PostgreSQLContainer("postgres:17-alpine");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);
 }
 @Autowired org.springframework.context.ApplicationContext context;
 @Autowired EntityManagerFactory emf;
 @Autowired DataSource ds;
 @Test void effectiveManagerAndSharedJdbcConnection(){
  var managers=context.getBeansOfType(PlatformTransactionManager.class);
  assertEquals(1,managers.size());var manager=managers.values().iterator().next();
  assertEquals(JpaTransactionManager.class,manager.getClass());assertFalse(((JpaTransactionManager)manager).isNestedTransactionAllowed());
  var sql=new JdbcTemplate(ds);
  new TransactionTemplate(manager).executeWithoutResult(s->{
   Object[] jpa=(Object[])EntityManagerFactoryUtils.getTransactionalEntityManager(emf).createNativeQuery("select pg_backend_pid(),txid_current()").getSingleResult();
   var jdbc=sql.queryForMap("select pg_backend_pid() pid,txid_current() xid");
   assertEquals(((Number)jpa[0]).longValue(),((Number)jdbc.get("pid")).longValue());assertEquals(((Number)jpa[1]).longValue(),((Number)jdbc.get("xid")).longValue());
   System.out.println("STEP3C CURRENT manager="+manager.getClass().getName()+" managers="+managers.keySet()+" "+jdbc);
  });
 }
}
