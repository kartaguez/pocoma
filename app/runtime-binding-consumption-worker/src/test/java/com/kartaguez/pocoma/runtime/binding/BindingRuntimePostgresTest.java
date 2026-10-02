package com.kartaguez.pocoma.runtime.binding;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.PocomaBindingConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.port.in.consumption.input.*;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.engine.read.binding.*;
import com.kartaguez.pocoma.locator.consumption.binding.BindingFactConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.*;

@SpringBootTest(classes=PocomaBindingConsumptionWorkerApplication.class, properties={
		"pocoma.binding-consumption.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate"})
class BindingRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static{POSTGRES.start();}
	@DynamicPropertySource static void database(DynamicPropertyRegistry r){
		r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
	}
	@Autowired JdbcTemplate jdbc; @Autowired DataSource dataSource;
	@Autowired ExternalIdentityBindingPort bindings; @Autowired ConsumptionOrchestrator orchestrator;
	@Autowired CurrentBindingProjectionPort projection;
	@Autowired TransactionRunner transactions; @Autowired Clock clock;
	@Autowired BindingFactConsumptionLocator locator; @Autowired AcquireConsumptionUseCase acquire;
	@Autowired ExecuteConsumptionUseCase execute; @Autowired HandleConsumptionFailureUseCase handleFailure;

	@BeforeEach void clean(){
		jdbc.execute("drop trigger if exists reject_current_binding on pocoma_read.current_external_identity_binding");
		jdbc.execute("drop function if exists pocoma_read.reject_current_binding()");
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "external_identity_binding_facts, external_identity_binding_occurrences, external_identity_binding_streams, external_identities, users cascade");
		jdbc.execute("truncate table pocoma_read.current_external_identity_binding");
	}

	@Test void attachDetachReattachTraversesAuthorityFactConsumptionAndCurrentBinding() {
		PocomaUserId user=user(1); insertUser(user); ExternalIdentity e=id("e2e");
		BindingId b1=transactions.runInTransaction(()->bindings.acquire(e,user)).bindingId();
		assertEquals(1L,count("external_identity_binding_facts")); orchestrator.run(input("a"));
		assertCurrent(e,1,CurrentBindingStatus.ATTACHED,b1,user);
		assertEquals(BindingDetachResult.DETACHED,transactions.runInTransaction(()->bindings.detach(e,b1)));
		orchestrator.run(input("b")); assertCurrent(e,2,CurrentBindingStatus.DETACHED,null,null);
		BindingId b2=transactions.runInTransaction(()->bindings.acquire(e,user)).bindingId();
		orchestrator.run(input("c")); assertCurrent(e,3,CurrentBindingStatus.ATTACHED,b2,user);
		assertEquals(3L,count("external_identity_binding_facts"));
	}

	@Test void lateCommitBeforeTheEphemeralCursorIsFoundOnTheNextScan() throws Exception {
		PocomaUserId u1=user(11),u2=user(12);insertUser(u1);insertUser(u2);
		ExternalIdentity early=id("a-early"), later=id("z-later"); BindingId b1=binding(11),b2=binding(12);
		insertStream(early,1);insertStream(later,1);reserve(early,u1,b1,1);reserve(later,u2,b2,1);UUID f1=UUID.randomUUID(),f2=UUID.randomUUID();
		try(Connection t1=dataSource.getConnection()){
			t1.setAutoCommit(false); insertFact(t1,f1,early,u1,b1);
			insertFact(null,f2,later,u2,b2);
			orchestrator.run(input("first-scan"));
			assertCurrent(later,1,CurrentBindingStatus.ATTACHED,b2,u2);
			assertTrue(transactions.runInTransaction(()->projection.find(early)).isEmpty());
			t1.commit();
		}
		orchestrator.run(input("second-scan"));
		assertCurrent(early,1,CurrentBindingStatus.ATTACHED,b1,u1);
		assertEquals(2L,jdbc.queryForObject("select count(*) from consumption_slots where status='DONE' and consumer_type='CURRENT_BINDING_PROJECTOR'",Long.class));
	}

	@Test void baselineFactsRebuildAttachedDetachedAndReattachedWithoutPrimaryBootstrap() {
		PocomaUserId firstUser=user(20), secondUser=user(21);
		insertUser(firstUser); insertUser(secondUser);
		ExternalIdentity attached=id("baseline-attached"), detached=id("baseline-detached"),
				sameUser=id("baseline-same-user"), otherUser=id("baseline-other-user");
		for (ExternalIdentity e : java.util.List.of(attached,detached,sameUser,otherUser)) {
			insertStream(e,0); BindingId b=binding(Math.abs(e.subject().hashCode())+100);
			reserve(e,firstUser,b,0); insertBaselineFact(e,firstUser,b);
			jdbc.update("insert into external_identities values (?,?,?,?)",e.issuer(),e.subject(),firstUser.value(),b.value());
		}
		assertEquals(4L,count("external_identity_binding_facts"));
		assertEquals(BindingDetachResult.DETACHED,transactions.runInTransaction(()->bindings.detach(detached,binding(Math.abs(detached.subject().hashCode())+100))));
		assertEquals(BindingDetachResult.DETACHED,transactions.runInTransaction(()->bindings.detach(sameUser,binding(Math.abs(sameUser.subject().hashCode())+100))));
		assertEquals(BindingDetachResult.DETACHED,transactions.runInTransaction(()->bindings.detach(otherUser,binding(Math.abs(otherUser.subject().hashCode())+100))));
		BindingId sameB=transactions.runInTransaction(()->bindings.acquire(sameUser,firstUser)).bindingId();
		BindingId otherB=transactions.runInTransaction(()->bindings.acquire(otherUser,secondUser)).bindingId();
		assertEquals(0L,count("pocoma_read.current_external_identity_binding"));
		var rebuild = orchestrator.run(input("facts-only-rebuild"));
		assertEquals(count("external_identity_binding_facts"), count("consumption_slots"),
				() -> rebuild + " " + jdbc.queryForList("select status from consumption_slots"));
		assertEquals(9L,jdbc.queryForObject("select count(*) from consumption_slots where status='DONE'",Long.class),
				() -> jdbc.queryForList("select c.failure_message from consumption_claims c where c.failure_message is not null").toString());
		assertEquals(0L,jdbc.queryForObject("select count(*) from consumption_claims where failure_message is not null",Long.class),
				() -> jdbc.queryForList("select failure_message from consumption_claims where failure_message is not null").toString());
		assertEquals(4L,count("pocoma_read.current_external_identity_binding"),
				() -> jdbc.queryForList("select issuer,subject,binding_revision from pocoma_read.current_external_identity_binding").toString());
		assertCurrent(attached,0,CurrentBindingStatus.ATTACHED,binding(Math.abs(attached.subject().hashCode())+100),firstUser);
		assertCurrent(detached,1,CurrentBindingStatus.DETACHED,null,null);
		assertCurrent(sameUser,2,CurrentBindingStatus.ATTACHED,sameB,firstUser);
		assertCurrent(otherUser,2,CurrentBindingStatus.ATTACHED,otherB,secondUser);
		assertEquals(4L,count("pocoma_read.current_external_identity_binding"));
		assertEquals(0L,jdbc.queryForObject("select count(*) from pocoma_read.current_external_identity_binding where source_event_id is null",Long.class));
		assertEquals(0L,jdbc.queryForObject("""
				select count(*) from (
				 select distinct on (issuer,subject) issuer,subject,binding_revision,fact_type,user_id,binding_id,event_id
				 from external_identity_binding_facts order by issuer,subject,binding_revision desc
				) f full join pocoma_read.current_external_identity_binding p
				 on p.issuer=f.issuer and p.subject=f.subject
				where f.event_id is null or p.source_event_id is null
				 or p.binding_revision<>f.binding_revision or p.source_event_id<>f.event_id
				 or p.binding_status<>f.fact_type
				 or (f.fact_type='ATTACHED' and (p.user_id is distinct from f.user_id or p.binding_id is distinct from f.binding_id))
				 or (f.fact_type='DETACHED' and (p.user_id is not null or p.binding_id is not null))
				""",Long.class));
		assertEquals(count("external_identity_binding_facts"),count("consumption_slots"));
		jdbc.execute("truncate table pocoma_read.current_external_identity_binding");
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims cascade");
		new SequentialConsumptionOrchestrator(locator,acquire,execute,handleFailure).run(input("facts-only-restart"));
		assertCurrent(detached,1,CurrentBindingStatus.DETACHED,null,null);
		assertCurrent(otherUser,2,CurrentBindingStatus.ATTACHED,otherB,secondUser);
		assertEquals(4L,count("pocoma_read.current_external_identity_binding"));
	}

	private void insertBaselineFact(ExternalIdentity e,PocomaUserId u,BindingId b) {
		jdbc.update("insert into external_identity_binding_facts(event_id,issuer,subject,binding_revision,fact_type,user_id,binding_id,recorded_at,partition_hash,record_origin) values (?,?,?,?,?,?,?,now(),hashtext(jsonb_build_array(?,?)::text),'MIGRATION_BASELINE')",
				UUID.randomUUID(),e.issuer(),e.subject(),0,"ATTACHED",u.value(),b.value(),e.issuer(),e.subject());
	}

	@Test void technicalFailureRetriesAfterAReconstructedScanAndThenFinalizesIdempotently() {
		PocomaUserId user=user(30);insertUser(user);ExternalIdentity e=id("retry");
		BindingId b=transactions.runInTransaction(()->bindings.acquire(e,user)).bindingId();
		jdbc.execute("create function pocoma_read.reject_current_binding() returns trigger language plpgsql as $$ begin raise exception 'temporary'; end $$");
		jdbc.execute("create trigger reject_current_binding before insert on pocoma_read.current_external_identity_binding for each row execute function pocoma_read.reject_current_binding()");
		orchestrator.run(input("retry-first"));
		assertEquals("PENDING",jdbc.queryForObject("select status from consumption_slots where consumer_type='CURRENT_BINDING_PROJECTOR'",String.class));
		assertEquals(0L,count("pocoma_read.current_external_identity_binding"));
		jdbc.execute("drop trigger reject_current_binding on pocoma_read.current_external_identity_binding");
		jdbc.execute("drop function pocoma_read.reject_current_binding()");
		jdbc.update("update consumption_slots set next_claim_at=current_timestamp");
		new SequentialConsumptionOrchestrator(locator,acquire,execute,handleFailure).run(input("restart-worker"));
		assertCurrent(e,1,CurrentBindingStatus.ATTACHED,b,user);
		assertEquals("DONE",jdbc.queryForObject("select status from consumption_slots where consumer_type='CURRENT_BINDING_PROJECTOR'",String.class));
	}

	@Test void lostClaimRollsBackProjectionBeforeWinnerAndMultipleWorkersConverge() throws Exception {
		PocomaUserId user=user(40);insertUser(user);ExternalIdentity e=id("lost-claim");
		BindingId b=transactions.runInTransaction(()->bindings.acquire(e,user)).bindingId();
		var located=locator.openSearch().next().orElseThrow();
		var stale=((AcquireResult.Acquired)acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),new WorkerId("stale"),new ClaimLease(Duration.ofMillis(1))))).claim();
		Thread.sleep(10);
		var winner=((AcquireResult.Acquired)acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),new WorkerId("winner"),new ClaimLease(Duration.ofSeconds(30))))).claim();
		assertThrows(RuntimeException.class,()->execute.execute(new ExecuteConsumptionInput(stale.slotId(),stale.claimId(),located.execution())));
		assertTrue(transactions.runInTransaction(()->projection.find(e)).isEmpty());
		execute.execute(new ExecuteConsumptionInput(winner.slotId(),winner.claimId(),located.execution()));
		assertCurrent(e,1,CurrentBindingStatus.ATTACHED,b,user);

		for(int i=41;i<51;i++){int n=i;PocomaUserId u=user(n);insertUser(u);transactions.runInTransaction(()->bindings.acquire(id("multi-"+n),u));}
		try(var pool=Executors.newFixedThreadPool(2)){
			var a=pool.submit(()->orchestrator.run(input("worker-a")));var c=pool.submit(()->orchestrator.run(input("worker-b")));
			a.get(10,TimeUnit.SECONDS);c.get(10,TimeUnit.SECONDS);
		}
		orchestrator.run(input("drain"));
		assertEquals(11L,count("pocoma_read.current_external_identity_binding"));
		assertEquals(11L,jdbc.queryForObject("select count(*) from consumption_slots where status='DONE' and consumer_type='CURRENT_BINDING_PROJECTOR'",Long.class));
	}

	private void insertUser(PocomaUserId u){jdbc.update("insert into users(user_id) values (?)",u.value());}
	private void insertStream(ExternalIdentity e,long revision){jdbc.update("insert into external_identity_binding_streams values (?,?,?)",e.issuer(),e.subject(),revision);}
	private void reserve(ExternalIdentity e,PocomaUserId u,BindingId b,long revision){jdbc.update("insert into external_identity_binding_occurrences values (?,?,?,?,?,now())",b.value(),e.issuer(),e.subject(),u.value(),revision);}
	private void insertFact(Connection c,UUID event,ExternalIdentity e,PocomaUserId u,BindingId b)throws Exception{
		String sql="insert into external_identity_binding_facts(event_id,issuer,subject,binding_revision,fact_type,user_id,binding_id,recorded_at,partition_hash) values (?,?,?,?,?,?,?,?,hashtext(jsonb_build_array(?,?)::text))";
		if(c==null){jdbc.update(sql,event,e.issuer(),e.subject(),1,"ATTACHED",u.value(),b.value(),Timestamp.from(Instant.now()),e.issuer(),e.subject());return;}
		try(var p=c.prepareStatement(sql)){p.setObject(1,event);p.setString(2,e.issuer());p.setString(3,e.subject());p.setLong(4,1);p.setString(5,"ATTACHED");p.setObject(6,u.value());p.setObject(7,b.value());p.setTimestamp(8,Timestamp.from(Instant.now()));p.setString(9,e.issuer());p.setString(10,e.subject());p.executeUpdate();}
	}
	private void assertCurrent(ExternalIdentity e,long r,CurrentBindingStatus s,BindingId b,PocomaUserId u){CurrentBinding current=transactions.runInTransaction(()->projection.find(e).orElseThrow());assertEquals(r,current.bindingRevision().value());assertEquals(s,current.status());assertEquals(b,current.bindingId());assertEquals(u,current.userId());}
	private long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
	private static ExternalIdentity id(String subject){return new ExternalIdentity("issuer",subject);}
	private static PocomaUserId user(int n){return new PocomaUserId(new UUID(0,n));}
	private static BindingId binding(int n){return new BindingId(new UUID(1,n));}
	private static ConsumptionOrchestrationInput input(String worker){return new ConsumptionOrchestrationInput(new WorkerId(worker),new ClaimLease(Duration.ofSeconds(30)),new ConsumptionOrchestrationBudget(100,100));}
}
