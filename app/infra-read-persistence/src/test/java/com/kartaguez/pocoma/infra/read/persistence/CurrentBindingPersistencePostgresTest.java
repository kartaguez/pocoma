package com.kartaguez.pocoma.infra.read.persistence;

import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBinding;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingApplyResult;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.JdbcCurrentBindingAdapter;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.flywaydb.core.Flyway;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.*;
import com.kartaguez.pocoma.engine.materialize.currentbinding.*;

@Testcontainers
class CurrentBindingPersistencePostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private TransactionTemplate tx; private JdbcCurrentBindingAdapter adapter; private DriverManagerDataSource ds; private JdbcTemplate jdbc;
	private final ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
	private final PocomaUserId user = new PocomaUserId(UUID.randomUUID());
	private final BindingId first = new BindingId(UUID.randomUUID());

	@BeforeEach void reset() {
		ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(ds);
		jdbc.execute("drop schema if exists pocoma_read cascade");
		new ReadStoreMigrator(ds, new ReadStoreProperties()).afterPropertiesSet();
		tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
		adapter = new JdbcCurrentBindingAdapter(new JdbcTemplate(ds), "pocoma_read");
	}

	@Test void appliesFirstUpdateTombstoneDuplicateStaleJumpAndOutOfOrderByRevisionOnly() {
		UUID e1=UUID.randomUUID(), e2=UUID.randomUUID(), e3=UUID.randomUUID();
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(attached(1, first, e1)));
		assertEquals(CurrentBindingApplyResult.DUPLICATE, apply(attached(1, first, e1)));
		BindingId second=new BindingId(UUID.randomUUID());
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(detached(3, second, e3)));
		assertEquals(CurrentBindingApplyResult.STALE, apply(detached(2, first, e2)));
		assertEquals(CurrentBindingApplyResult.STALE, apply(attached(1, first, e1)));
		CurrentBinding current=find();
		assertEquals(3, current.bindingRevision().value());
		assertEquals(CurrentBindingStatus.DETACHED,current.status());
		assertNull(current.userId()); assertNull(current.bindingId());
	}

	@Test void attachDetachReattachCannotBeResurrectedByOldFact() {
		UUID firstEvent = UUID.randomUUID(), detachedEvent = UUID.randomUUID(), thirdEvent = UUID.randomUUID();
		BindingId third = new BindingId(UUID.randomUUID());
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(attached(1, first, firstEvent)));
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(detached(2, first, detachedEvent)));
		assertEquals(CurrentBindingStatus.DETACHED, find().status());
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(attached(3, third, thirdEvent)));
		assertEquals(CurrentBindingApplyResult.DUPLICATE, apply(attached(3, third, thirdEvent)));
		assertThrows(CurrentBindingInvariantException.class,
				() -> apply(attached(3, first, thirdEvent)));
		assertEquals(CurrentBindingApplyResult.STALE, apply(attached(1, first, firstEvent)));
		assertEquals(3, find().bindingRevision().value());
		assertEquals(third, find().bindingId());
	}

	@Test void equalRevisionDivergenceIsObservableAndBootstrapZeroNeverOverwritesOne() {
		UUID event=UUID.randomUUID();
		apply(attached(1, first, event));
		assertThrows(CurrentBindingInvariantException.class,
				()->apply(attached(1,new BindingId(UUID.randomUUID()),event)));
		assertEquals(CurrentBindingApplyResult.STALE, apply(new CurrentBinding(identity,new BindingRevision(0),
				CurrentBindingStatus.ATTACHED,user,first,UUID.randomUUID(),Instant.now())));
		assertEquals(1,find().bindingRevision().value());
	}

	@Test void detachedShapeAndAbsenceAreDistinctAndEnforcedInSql() {
		assertTrue(tx.execute(s -> adapter.find(identity)).isEmpty());
		UUID source = UUID.randomUUID();
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(detached(2, first, source)));
		assertEquals(source, find().sourceEventId());
		assertNull(find().bindingId());
		assertEquals(1L, jdbc.queryForObject("select count(*) from pocoma_read.current_external_identity_binding", Long.class));
		assertThrows(DataAccessException.class, () -> jdbc.update("update pocoma_read.current_external_identity_binding set binding_id=? where issuer=? and subject=?", first.value(), identity.issuer(), identity.subject()));
		assertThrows(DataAccessException.class, () -> jdbc.update("update pocoma_read.current_external_identity_binding set user_id=? where issuer=? and subject=?", user.value(), identity.issuer(), identity.subject()));
		assertThrows(DataAccessException.class, () -> jdbc.update("update pocoma_read.current_external_identity_binding set binding_status='ATTACHED' where issuer=? and subject=?", identity.issuer(), identity.subject()));
		BindingId second = new BindingId(UUID.randomUUID());
		assertEquals(CurrentBindingApplyResult.APPLIED, apply(attached(3, second, UUID.randomUUID())));
		assertEquals(second, find().bindingId());
	}

	@Test void upgradeNormalizesOldTombstoneWithoutLosingIdentityRevisionOrSource() {
		jdbc.execute("drop schema pocoma_read cascade");
		Flyway.configure().dataSource(ds).defaultSchema("pocoma_read").schemas("pocoma_read")
				.createSchemas(true).locations("classpath:db/read-store/migration").target("9").load().migrate();
		UUID source = UUID.randomUUID();
		jdbc.update("insert into pocoma_read.current_external_identity_binding values (?,?,?,?,?,?,?,now())",
				identity.issuer(), identity.subject(), 2, "DETACHED", null, first.value(), source);
		Flyway.configure().dataSource(ds).defaultSchema("pocoma_read").schemas("pocoma_read")
				.createSchemas(true).locations("classpath:db/read-store/migration").target("11").load().migrate();
		CurrentBinding current = find();
		assertEquals(identity, current.externalIdentity());
		assertEquals(2, current.bindingRevision().value());
		assertEquals(source, current.sourceEventId());
		assertNull(current.userId()); assertNull(current.bindingId());
	}

	@Test void revisionZeroUpgradeUsesTheRealBaselineFactEventId() {
		jdbc.execute("drop schema pocoma_read cascade");
		Flyway.configure().dataSource(ds).defaultSchema("pocoma_read").schemas("pocoma_read")
				.createSchemas(true).locations("classpath:db/read-store/migration").target("9").load().migrate();
		UUID source = UUID.randomUUID();
		jdbc.execute("create table public.external_identity_binding_facts (event_id uuid, issuer text, subject text, binding_revision bigint, fact_type text, user_id uuid, binding_id uuid)");
		try {
			jdbc.update("insert into public.external_identity_binding_facts values (?,?,?,?,?,?,?)",
					source, identity.issuer(), identity.subject(), 0, "ATTACHED", user.value(), first.value());
			jdbc.update("insert into pocoma_read.current_external_identity_binding values (?,?,0,'ATTACHED',?,?,null,now())",
					identity.issuer(), identity.subject(), user.value(), first.value());
			new ReadStoreMigrator(ds, new ReadStoreProperties()).afterPropertiesSet();
			assertEquals(source, find().sourceEventId());
			assertThrows(DataAccessException.class, () -> jdbc.update("update pocoma_read.current_external_identity_binding set source_event_id=null"));
		} finally {
			jdbc.execute("drop table public.external_identity_binding_facts");
		}
	}

	private CurrentBindingApplyResult apply(CurrentBinding b){return tx.execute(s->adapter.apply(b));}
	private CurrentBinding find(){return tx.execute(s->adapter.find(identity).orElseThrow());}
	private CurrentBinding attached(long r,BindingId b,UUID event){return new CurrentBinding(identity,new BindingRevision(r),CurrentBindingStatus.ATTACHED,user,b,event,Instant.now());}
	private CurrentBinding detached(long r,BindingId b,UUID event){return new CurrentBinding(identity,new BindingRevision(r),CurrentBindingStatus.DETACHED,null,null,event,Instant.now());}
}
