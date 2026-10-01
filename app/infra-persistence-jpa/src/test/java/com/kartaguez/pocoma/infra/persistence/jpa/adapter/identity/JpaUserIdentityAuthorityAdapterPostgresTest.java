package com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.useridentity.BindingAcquireResult;
import com.kartaguez.pocoma.domain.useridentity.BindingDetachResult;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.UserJdbcRepository;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JpaUserIdentityAuthorityAdapterPostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaUserAuthorityAdapter users;
	@Autowired private JpaExternalIdentityBindingAdapter bindings;
	@Autowired private JpaExternalIdentityResolverAdapter legacyResolver;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	private ExecutorService executor;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("delete from external_identities");
		jdbc.update("delete from users");
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void stopExecutor() {
		executor.shutdownNow();
	}

	@Test
	void createsAndFindsMinimalUsersAndRequiresTheCallingTransaction() {
		User user = user(1);

		inTransaction(() -> { users.create(user); return null; });

		assertEquals(user, inTransaction(() -> users.findById(user.id()).orElseThrow()));
		assertTrue(inTransaction(() -> users.findById(userId(2))).isEmpty());
		assertEquals(0, jdbc.queryForObject("select count(*) from external_identities", Integer.class));
		assertThrows(IllegalTransactionStateException.class, () -> users.create(user(3)));
		assertThrows(IllegalTransactionStateException.class, () -> users.findById(user.id()));
	}

	@Test
	void resolvesLegacyAndExactCurrentOccurrenceOnly() {
		User user = createUser(10);
		ExternalIdentity identity = identity("issuer", "subject");
		BindingId bindingId = bindingId(11);
		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity, user.id(), bindingId)));

		assertEquals(user.id(), inTransaction(() -> legacyResolver.findUserId(identity)).orElseThrow());
		assertEquals(user.id(), inTransaction(() -> bindings.findUserId(identity, bindingId)).orElseThrow());
		assertTrue(inTransaction(() -> bindings.findUserId(identity, bindingId(12))).isEmpty());
		assertTrue(inTransaction(() -> bindings.findUserId(identity("issuer", "other"), bindingId)).isEmpty());
		assertTrue(inTransaction(() -> bindings.lockCurrentBinding(identity, bindingId(12))).isEmpty());
		assertThrows(IllegalTransactionStateException.class, () -> bindings.findUserId(identity, bindingId));
		assertThrows(IllegalTransactionStateException.class, () -> bindings.lockCurrentBinding(identity, bindingId));
	}

	@Test
	void acquireUsesDatabaseAuthorityWithoutRevealingTheExistingOwner() {
		User first = createUser(20);
		User second = createUser(21);
		ExternalIdentity identity = identity("issuer", "occupied");
		BindingId firstBinding = bindingId(22);

		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity, first.id(), firstBinding)));
		assertEquals(BindingAcquireResult.CONFLICT,
				inTransaction(() -> bindings.acquire(identity, second.id(), bindingId(23))));
		assertEquals(first.id(), inTransaction(() -> bindings.findUserId(identity, firstBinding)).orElseThrow());
	}

	@Test
	void bindingIdIsGloballyUniqueAndUserForeignKeyIsAuthoritative() {
		User user = createUser(30);
		BindingId bindingId = bindingId(31);
		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity("issuer", "one"), user.id(), bindingId)));

		assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() ->
				bindings.acquire(identity("issuer", "two"), user.id(), bindingId)));
		assertThrows(DataIntegrityViolationException.class, () -> inTransaction(() ->
				bindings.acquire(identity("issuer", "unknown-user"), userId(999), bindingId(32))));
		assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update("delete from users where user_id = ?", user.id().value()));
	}

	@Test
	void staleDetachCannotDeleteAReattachedOccurrence() {
		User user = createUser(40);
		ExternalIdentity identity = identity("issuer", "reattach");
		BindingId first = bindingId(41);
		BindingId second = bindingId(42);

		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity, user.id(), first)));
		assertEquals(BindingDetachResult.DETACHED,
				inTransaction(() -> bindings.detach(identity, first)));
		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity, user.id(), second)));
		assertEquals(BindingDetachResult.NOT_CURRENT,
				inTransaction(() -> bindings.detach(identity, first)));
		assertEquals(user.id(), inTransaction(() -> bindings.findUserId(identity, second)).orElseThrow());
	}

	@Test
	void concurrentAcquireHasExactlyOneWinnerAndOneOpaqueConflict() throws Exception {
		User first = createUser(50);
		User second = createUser(51);
		ExternalIdentity identity = identity("issuer", "race");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<BindingAcquireResult> one = executor.submit(() -> concurrentAcquire(
				identity, first.id(), bindingId(52), ready, start));
		Future<BindingAcquireResult> two = executor.submit(() -> concurrentAcquire(
				identity, second.id(), bindingId(53), ready, start));
		assertTrue(ready.await(5, TimeUnit.SECONDS));
		start.countDown();

		List<BindingAcquireResult> results = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
		assertEquals(1, results.stream().filter(BindingAcquireResult.ACQUIRED::equals).count());
		assertEquals(1, results.stream().filter(BindingAcquireResult.CONFLICT::equals).count());
		assertEquals(1, jdbc.queryForObject("select count(*) from external_identities where issuer=? and subject=?",
				Integer.class, identity.issuer(), identity.subject()));
	}

	@Test
	void exactBindingLockPreventsDetachUntilTheSurroundingTransactionCommits() throws Exception {
		User user = createUser(60);
		ExternalIdentity identity = identity("issuer", "locked");
		BindingId bindingId = bindingId(61);
		assertEquals(BindingAcquireResult.ACQUIRED,
				inTransaction(() -> bindings.acquire(identity, user.id(), bindingId)));

		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		CountDownLatch detachStarted = new CountDownLatch(1);
		AtomicInteger detachBackendPid = new AtomicInteger();

		Future<?> locker = executor.submit(() -> inTransaction(() -> {
			assertEquals(user.id(), bindings.lockCurrentBinding(identity, bindingId).orElseThrow());
			locked.countDown();
			await(releaseLock);
			return null;
		}));
		assertTrue(locked.await(5, TimeUnit.SECONDS));

		Future<BindingDetachResult> detacher = executor.submit(() -> inTransaction(() -> {
			detachBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
			detachStarted.countDown();
			return bindings.detach(identity, bindingId);
		}));
		assertTrue(detachStarted.await(5, TimeUnit.SECONDS));
		assertTrue(awaitPostgresLockWait(detachBackendPid.get(), Duration.ofSeconds(5)));
		assertFalse(detacher.isDone());

		releaseLock.countDown();
		locker.get(5, TimeUnit.SECONDS);
		assertEquals(BindingDetachResult.DETACHED, detacher.get(5, TimeUnit.SECONDS));
		assertTrue(inTransaction(() -> bindings.findUserId(identity, bindingId)).isEmpty());
	}

	private BindingAcquireResult concurrentAcquire(ExternalIdentity identity, PocomaUserId userId,
			BindingId bindingId, CountDownLatch ready, CountDownLatch start) {
		return inTransaction(() -> {
			ready.countDown();
			await(start);
			return bindings.acquire(identity, userId, bindingId);
		});
	}

	private boolean awaitPostgresLockWait(int backendPid, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			Boolean waiting = jdbc.queryForObject("""
					select exists (
					  select 1 from pg_stat_activity
					  where pid = ? and wait_event_type = 'Lock'
					)
					""", Boolean.class, backendPid);
			if (Boolean.TRUE.equals(waiting)) return true;
			Thread.sleep(10);
		}
		return false;
	}

	private User createUser(int value) {
		User user = user(value);
		inTransaction(() -> { users.create(user); return null; });
		return user;
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test barrier");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static ExternalIdentity identity(String issuer, String subject) {
		return new ExternalIdentity(issuer, subject);
	}

	private static User user(int value) {
		return new User(userId(value));
	}

	private static PocomaUserId userId(int value) {
		return new PocomaUserId(uuid(value));
	}

	private static BindingId bindingId(int value) {
		return new BindingId(uuid(value));
	}

	private static UUID uuid(int value) {
		return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({JpaUserAuthorityAdapter.class, JpaExternalIdentityBindingAdapter.class,
			JpaExternalIdentityResolverAdapter.class, UserJdbcRepository.class,
			ExternalIdentityJdbcRepository.class})
	static class TestApplication {}
}
