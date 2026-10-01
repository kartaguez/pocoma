package com.kartaguez.pocoma.infra.read.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.engine.read.binding.*;

@Testcontainers
class CurrentBindingPersistencePostgresTest {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	private TransactionTemplate tx; private JdbcCurrentBindingAdapter adapter;
	private final ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
	private final PocomaUserId user = new PocomaUserId(UUID.randomUUID());
	private final BindingId first = new BindingId(UUID.randomUUID());

	@BeforeEach void reset() {
		var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		new JdbcTemplate(ds).execute("drop schema if exists pocoma_read cascade");
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
		assertNull(current.userId()); assertEquals(second,current.bindingId());
	}

	@Test void equalRevisionDivergenceIsObservableAndBootstrapZeroNeverOverwritesOne() {
		UUID event=UUID.randomUUID();
		apply(attached(1, first, event));
		assertThrows(CurrentBindingInvariantException.class,
				()->apply(attached(1,new BindingId(UUID.randomUUID()),event)));
		assertEquals(CurrentBindingApplyResult.STALE, apply(new CurrentBinding(identity,new BindingRevision(0),
				CurrentBindingStatus.ATTACHED,user,first,null,Instant.now())));
		assertEquals(1,find().bindingRevision().value());
	}

	private CurrentBindingApplyResult apply(CurrentBinding b){return tx.execute(s->adapter.apply(b));}
	private CurrentBinding find(){return tx.execute(s->adapter.find(identity).orElseThrow());}
	private CurrentBinding attached(long r,BindingId b,UUID event){return new CurrentBinding(identity,new BindingRevision(r),CurrentBindingStatus.ATTACHED,user,b,event,Instant.now());}
	private CurrentBinding detached(long r,BindingId b,UUID event){return new CurrentBinding(identity,new BindingRevision(r),CurrentBindingStatus.DETACHED,null,b,event,Instant.now());}
}
