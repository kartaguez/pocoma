package com.kartaguez.pocoma.architecture.ccr;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.materialize.commandresult.MaterializeCommandResultService;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionQueryPort;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.PublishedCommandResult;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandResultSource;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandResultStore;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;
import com.kartaguez.pocoma.supra.consume.commandresult.CommandResultConsumptionLocator;

/** Real services join caller-owned transactions; gates never replace a CAS or a lock. */
@SpringBootTest(classes = CommandResultTakeoverRacePostgresTest.TestApplication.class, properties = {
		"pocoma.command-result-consumption.enabled=false", "spring.jpa.hibernate.ddl-auto=validate" })
class CommandResultTakeoverRacePostgresTest {
	private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
	private static final Instant T1 = T0.plusSeconds(31);
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final int TIMEOUT_SECONDS = 20;
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }
	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired AcquireConsumptionUseCase acquire;
	@Autowired ExecuteConsumptionUseCase execute;
	@Autowired ConsumptionQueryPort consumption;
	@Autowired JdbcCommandResultSource source;
	@Autowired JdbcCommandResultStore store;
	@Autowired MaterializeCommandResultService materializer;
	@TestBean(name = "commandResultClock", methodName = "testClock", enforceOverride = true)
	Clock clock;
	static Clock testClock() { return new ControlledClock(new AtomicReference<>(T0), ZoneOffset.UTC); }

	@BeforeEach void clean() {
		((ControlledClock) clock).set(T0);
		jdbc.execute("truncate table command_results, command_terminal_events, command_outcomes, recorded_commands cascade");
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims cascade");
	}

	@Test void inFlightPublicationLosesFenceAndUnblocksWinnerPublication() throws Exception {
		Seed seed = seed();
		Claim stale = initialClaim(seed);
		TxProbe first = new TxProbe("C1-execute"), acquisition = new TxProbe("C2-acquire");
		TxProbe second = new TxProbe("C2-execute");
		try (var pool = Executors.newFixedThreadPool(2); var inserted = new Gate("C1-insert-before-CAS")) {
			Future<?> losing = pool.submit(() -> inTransaction(first, () -> execute.execute(
					execution(stale, seed, first, inserted::pause, true))));
			inserted.awaitReached();
			assertNoPublication();
			expire(stale);
			Claim winner = acquired(inTransaction(acquisition, () -> acquire(seed, "C2", acquisition)));
			assertDistinct(first, acquisition);
			assertCommitted(acquisition);
			assertTakeover(stale, winner);
			Future<?> winning = pool.submit(() -> inTransaction(second, () -> execute.execute(
					execution(winner, seed, second, () -> {}, false))));
			second.awaitStarted();
			awaitBlocked(second, first, "publication");
			// C2 has committed its claim, but its INSERT is waiting on C1, not holding the slot.
			assertPending(winner, 2);
			assertNoPublication();
			inserted.close();
			LostClaimException lost = failure(losing, LostClaimException.class);
			assertEquals(stale.claimId(), lost.claimId());
			assertEquals(stale.slotId(), lost.slotId());
			winning.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertRolledBack(first);
			assertCommitted(second);
			assertFinished(seed, winner, List.of(stale.invalidateForTakeoverAt(T1), winner.succeedAt(T1)));
		}
	}

	/** S1 is subsumed here: C2 is demonstrably waiting when C1 commits its final CAS. */
	@Test void committedFinalCasMakesWaitingTakeoverObserveDone() throws Exception {
		resolveFinalCasBeforeTakeover(false);
	}

	@Test void rolledBackFinalCasReleasesSlotAndAllowsTakeover() throws Exception {
		resolveFinalCasBeforeTakeover(true);
	}

	private void resolveFinalCasBeforeTakeover(boolean rollback) throws Exception {
		Seed seed = seed();
		Claim original = initialClaim(seed);
		expire(original);
		TxProbe first = new TxProbe("C1-after-CAS"), second = new TxProbe("C2-acquire");
		try (var pool = Executors.newFixedThreadPool(2); var beforeCommit = new Gate("C1-CAS-before-resolution")) {
			Future<?> finalization = pool.submit(() -> inTransaction(first, () -> {
				execute.execute(execution(original, seed, first, () -> {}, false));
				// This is after the real CAS and claim closure, but before the outer transaction resolves.
				assertEquals(ConsumptionStatus.DONE, consumption.findSlot(original.slotId()).orElseThrow().status());
				assertEquals(original.succeedAt(T1), consumption.findClaim(original.claimId()).orElseThrow());
				assertEquals(1L, count("consumption_inputs"));
				beforeCommit.pause();
				if (rollback) throw new RollbackMarker();
				return null;
			}));
			beforeCommit.awaitReached();
			assertPending(original, 1);
			assertEquals(List.of(original), consumption.findClaims(original.slotId()));
			assertNoPublication();
			Future<AcquireResult> takeover = pool.submit(() -> inTransaction(second,
					() -> acquire(seed, "C2", second)));
			second.awaitStarted();
			awaitBlocked(second, first, "slot");
			assertPending(original, 1);
			assertNoPublication();
			beforeCommit.close();
			if (rollback) {
				failure(finalization, RollbackMarker.class);
				Claim winner = acquired(takeover.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				assertRolledBack(first);
				assertCommitted(second);
				assertTakeover(original, winner);
				assertNoPublication();
				finish(seed, original, winner);
			} else {
				finalization.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				assertAlreadyDone(takeover.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				assertCommitted(first);
				assertCommitted(second);
				assertFinished(seed, original, List.of(original.succeedAt(T1)));
			}
		}
	}

	@Test void twoConcurrentTakeoversInstallOnlyOneCurrentClaim() throws Exception {
		Seed seed = seed();
		Claim stale = initialClaim(seed);
		expire(stale);
		TxProbe second = new TxProbe("C2-acquire"), third = new TxProbe("C3-acquire");
		CyclicBarrier start = new CyclicBarrier(3);
		AtomicReference<Contender> owner = new AtomicReference<>();
		try (var pool = Executors.newFixedThreadPool(2); var acquired = new Gate("takeover-before-commit")) {
			Future<AcquireResult> c2 = pool.submit(() -> contend(seed, "C2", second, start, owner, acquired));
			Future<AcquireResult> c3 = pool.submit(() -> contend(seed, "C3", third, start, owner, acquired));
			await(start);
			acquired.awaitReached();
			Contender winning = owner.get();
			assertNotNull(winning);
			TxProbe waiting = winning.probe() == second ? third : second;
			awaitBlocked(waiting, winning.probe(), "slot");
			assertPending(stale, 1);
			assertEquals(List.of(stale), consumption.findClaims(stale.slotId()));
			assertNoPublication();
			acquired.close();
			AcquireResult r2 = c2.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			AcquireResult r3 = c3.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			AcquireResult won = winning.probe() == second ? r2 : r3;
			AcquireResult busy = winning.probe() == second ? r3 : r2;
			Claim winner = acquired(won);
			assertEquals(winning.claim(), winner);
			assertEquals(winner.leaseUntil(), assertInstanceOf(AcquireResult.Busy.class, busy).leaseUntil());
			assertCommitted(second);
			assertCommitted(third);
			assertTakeover(stale, winner);
			assertLostWithoutPublication(seed, stale);
			finish(seed, stale, winner);
		}
	}

	private AcquireResult contend(Seed seed, String worker, TxProbe probe, CyclicBarrier start,
			AtomicReference<Contender> owner, Gate acquired) {
		return inTransaction(probe, () -> {
			await(start);
			AcquireResult result = acquire(seed, worker, probe);
			if (result instanceof AcquireResult.Acquired won) {
				assertTrue(owner.compareAndSet(null, new Contender(probe, won.claim())));
				assertPending(won.claim(), 2); // own uncommitted installation
				acquired.pause();
			}
			return result;
		});
	}

	@Test void rolledBackTakeoverLetsWaitingContenderReuseAttemptTwo() throws Exception {
		Seed seed = seed();
		Claim stale = initialClaim(seed);
		expire(stale);
		TxProbe second = new TxProbe("C2-rollback-acquire"), third = new TxProbe("C3-acquire");
		AtomicReference<Claim> abandoned = new AtomicReference<>();
		try (var pool = Executors.newFixedThreadPool(2); var installed = new Gate("C2-install-before-rollback")) {
			Future<?> rollback = pool.submit(() -> inTransaction(second, () -> {
				Claim claim = acquired(acquire(seed, "C2", second));
				abandoned.set(claim);
				assertPending(claim, 2);
				assertEquals(stale.invalidateForTakeoverAt(T1), consumption.findClaim(stale.claimId()).orElseThrow());
				installed.pause();
				throw new RollbackMarker();
			}));
			installed.awaitReached();
			Future<AcquireResult> takeover = pool.submit(() -> inTransaction(third,
					() -> acquire(seed, "C3", third)));
			third.awaitStarted();
			awaitBlocked(third, second, "slot");
			assertPending(stale, 1);
			assertEquals(List.of(stale), consumption.findClaims(stale.slotId()));
			assertNoPublication();
			installed.close();
			failure(rollback, RollbackMarker.class);
			Claim winner = acquired(takeover.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			assertRolledBack(second);
			assertCommitted(third);
			assertEquals(new WorkerId("C3"), winner.claimedBy());
			assertNotEquals(abandoned.get().claimId(), winner.claimId());
			assertTrue(consumption.findClaim(abandoned.get().claimId()).isEmpty());
			assertTakeover(stale, winner);
			assertLostWithoutPublication(seed, abandoned.get());
			finish(seed, stale, winner);
		}
	}

	private <T> T inTransaction(TxProbe probe, Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			assertTrue(status.isNewTransaction());
			TxInfo info = currentTransaction();
			assertEquals("read committed", info.isolation());
			probe.info.set(info);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCompletion(int status) {
					probe.completion.set(status);
					System.out.printf("RESOLVED name=%s pid=%d xid=%d status=%d%n", probe.name, info.pid(), info.xid(), status);
				}
			});
			System.out.printf("TX name=%s pid=%d xid=%d isolation=%s%n", probe.name, info.pid(), info.xid(), info.isolation());
			probe.started.countDown();
			return action.get();
		});
	}

	private TxInfo currentTransaction() {
		assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
		return jdbc.queryForObject("""
			select pg_backend_pid() as pid, txid_current() as xid,
			       current_setting('transaction_isolation') as isolation
			""", (rs, row) -> new TxInfo(rs.getInt("pid"), rs.getLong("xid"), rs.getString("isolation")));
	}

	private AcquireResult acquire(Seed seed, String worker, TxProbe probe) {
		return acquire.acquire(new AcquireConsumptionInput(CommandResultConsumptionLocator.key(seed.eventId()),
				new WorkerId(worker), LEASE, () -> {
					assertEquals(probe.info.get(), currentTransaction());
					return true;
				}));
	}

	private ExecuteConsumptionInput execution(Claim claim, Seed seed, TxProbe probe, Runnable afterInsert, boolean marker) {
		return new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), context -> {
			assertEquals(probe.info.get(), currentTransaction());
			materializer.materialize(seed.commandId(), source.reload(seed.eventId()));
			assertEquals(1L, count("command_results"));
			assertResultContent(seed);
			afterInsert.run();
			var inputs = new ArrayList<ConsumptionInput>();
			inputs.add(new ConsumptionInput(context.slotId(), "COMMAND_TERMINAL_EVENT", seed.eventId().toString(), 1));
			if (marker) inputs.add(new ConsumptionInput(context.slotId(), "STALE_CALLBACK", claim.claimId().value().toString(), 1));
			return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), inputs, List.of());
		});
	}

	private void awaitBlocked(TxProbe waiter, TxProbe owner, String kind) {
		waiter.awaitStarted();
		assertDistinct(waiter, owner);
		long deadline = System.nanoTime() + Duration.ofSeconds(TIMEOUT_SECONDS).toNanos();
		while (System.nanoTime() < deadline) {
			var observed = jdbc.query("""
				select pg_backend_pid() as observer_pid, query
				from pg_stat_activity where pid=? and ?=any(pg_blocking_pids(pid))
				and wait_event_type='Lock'
				""", (rs, row) -> new BlockInfo(rs.getInt("observer_pid"), rs.getString("query")),
					waiter.info.get().pid(), owner.info.get().pid());
			if (observed.isEmpty()) continue;
			BlockInfo block = observed.getFirst();
			assertNotEquals(waiter.info.get().pid(), block.observerPid());
			assertNotEquals(owner.info.get().pid(), block.observerPid());
			String query = block.query().toLowerCase(Locale.ROOT);
			if (kind.equals("publication")) {
				assertTrue(query.contains("insert into command_results") && query.contains("on conflict"), query);
			} else {
				// PostgreSQL may wait during the idempotent INSERT or the following SELECT FOR UPDATE.
				assertTrue(query.contains("consumption_slots") && (query.contains("for update") || query.contains("on conflict")), query);
			}
			assertEquals(-1, waiter.completion.get());
			assertEquals(-1, owner.completion.get());
			System.out.printf("BLOCK kind=%s owner_pid=%d waiter_pid=%d observer_pid=%d query=%s%n",
					kind, owner.info.get().pid(), waiter.info.get().pid(), block.observerPid(), query.replaceAll("\\s+", " "));
			return;
		}
		fail("No PostgreSQL lock wait observed: " + waiter.name + " behind " + owner.name);
	}

	private void assertDistinct(TxProbe first, TxProbe second) {
		assertNotNull(first.info.get());
		assertNotNull(second.info.get());
		assertNotEquals(first.info.get().pid(), second.info.get().pid());
		assertNotEquals(first.info.get().xid(), second.info.get().xid());
	}

	private void assertCommitted(TxProbe probe) { assertEquals(TransactionSynchronization.STATUS_COMMITTED, probe.completion.get()); }
	private void assertRolledBack(TxProbe probe) { assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, probe.completion.get()); }

	private static <T extends Throwable> T failure(Future<?> future, Class<T> expected) {
		ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		return assertInstanceOf(expected, error.getCause());
	}

	private Claim initialClaim(Seed seed) {
		TxProbe initial = new TxProbe("C1-acquire");
		Claim claim = acquired(inTransaction(initial, () -> acquire(seed, "C1", initial)));
		assertCommitted(initial);
		assertEquals(T0, claim.claimedAt());
		assertEquals(T0.plus(LEASE.duration()), claim.leaseUntil());
		assertEquals(1, claim.attemptNumber());
		assertPending(claim, 1);
		return claim;
	}

	private static Claim acquired(AcquireResult result) { return assertInstanceOf(AcquireResult.Acquired.class, result).claim(); }
	private static void assertAlreadyDone(AcquireResult result) {
		var done = assertInstanceOf(AcquireResult.AlreadyDone.class, result);
		assertEquals(TerminalOutcome.SUCCESS, done.outcome());
		assertEquals(Optional.empty(), done.reason());
	}

	private void expire(Claim claim) {
		((ControlledClock) clock).set(T1);
		assertTrue(claim.isLeaseExpiredAt(clock.instant()));
		assertEquals(claim, consumption.findClaim(claim.claimId()).orElseThrow());
		assertPending(claim, 1);
	}

	private void assertPending(Claim current, long revision) {
		var slot = consumption.findSlot(current.slotId()).orElseThrow();
		assertEquals(ConsumptionStatus.PENDING, slot.status());
		assertEquals(Optional.of(current.claimId()), slot.currentClaimId());
		assertEquals(revision, slot.revision());
		assertEquals(Optional.empty(), slot.terminalOutcome());
		assertEquals(Optional.empty(), slot.terminalReason());
		assertEquals(Optional.empty(), slot.doneAt());
	}

	private void assertTakeover(Claim stale, Claim winner) {
		assertNotEquals(stale.claimId(), winner.claimId());
		assertEquals(stale.slotId(), winner.slotId());
		assertEquals(2, winner.attemptNumber());
		assertEquals(T1, winner.claimedAt());
		assertEquals(T1.plus(LEASE.duration()), winner.leaseUntil());
		assertPending(winner, 2);
		assertEquals(List.of(stale.invalidateForTakeoverAt(T1), winner), consumption.findClaims(winner.slotId()));
	}

	private void assertNoPublication() {
		assertEquals(0L, count("command_results"));
		assertEquals(0L, count("consumption_inputs"));
		assertEquals(0L, count("consumption_results"));
	}

	private void assertLostWithoutPublication(Seed seed, Claim stale) {
		var slot = consumption.findSlot(stale.slotId()).orElseThrow();
		var claims = consumption.findClaims(stale.slotId());
		TxProbe loser = new TxProbe("stale-execute");
		LostClaimException lost = assertThrows(LostClaimException.class, () -> inTransaction(loser,
				() -> execute.execute(execution(stale, seed, loser, () -> {}, true))));
		assertEquals(stale.claimId(), lost.claimId());
		assertEquals(stale.slotId(), lost.slotId());
		assertRolledBack(loser);
		assertEquals(slot, consumption.findSlot(stale.slotId()).orElseThrow());
		assertEquals(claims, consumption.findClaims(stale.slotId()));
		assertNoPublication();
	}

	private void finish(Seed seed, Claim stale, Claim winner) {
		TxProbe finishing = new TxProbe("winner-execute");
		inTransaction(finishing, () -> execute.execute(execution(winner, seed, finishing, () -> {}, false)));
		assertCommitted(finishing);
		assertFinished(seed, winner, List.of(stale.invalidateForTakeoverAt(T1), winner.succeedAt(T1)));
	}

	private void assertFinished(Seed seed, Claim winner, List<Claim> history) {
		assertEquals(1L, count("consumption_slots"));
		var slot = consumption.findSlot(winner.slotId()).orElseThrow();
		assertEquals(ConsumptionStatus.DONE, slot.status());
		assertEquals(Optional.empty(), slot.currentClaimId());
		assertEquals(Optional.of(TerminalOutcome.SUCCESS), slot.terminalOutcome());
		assertEquals(Optional.empty(), slot.terminalReason());
		assertEquals(Optional.of(T1), slot.doneAt());
		assertEquals(winner.attemptNumber() + 1L, slot.revision());
		assertEquals(history, consumption.findClaims(winner.slotId()));
		assertEquals((long) history.size(), count("consumption_claims"));
		assertEquals(1L, count("command_results"));
		assertResultContent(seed);
		assertEquals(List.of(new ConsumptionInput(winner.slotId(), "COMMAND_TERMINAL_EVENT", seed.eventId().toString(), 1)),
				jdbc.query("select slot_id, subject_type, subject_id, subject_version from consumption_inputs",
						(rs, row) -> new ConsumptionInput(rs.getObject("slot_id", UUID.class), rs.getString("subject_type"),
								rs.getString("subject_id"), rs.getLong("subject_version"))));
		assertEquals(0L, count("consumption_results"));
		assertAlreadyDone(acquire.acquire(new AcquireConsumptionInput(CommandResultConsumptionLocator.key(seed.eventId()),
				new WorkerId("replay"), LEASE)));
		assertEquals(slot, consumption.findSlot(winner.slotId()).orElseThrow());
		assertEquals(history, consumption.findClaims(winner.slotId()));
		assertEquals(1L, count("command_results"));
	}

	private void assertResultContent(Seed seed) {
		CommandId command = new CommandId(seed.commandId());
		assertEquals(new ImmutableCommandResult(new ExternalIdentity("issuer", "subject"),
				new PublishedCommandResult.Applied(command, seed.potId(), 7, T0)), store.find(command).orElseThrow());
	}

	private Seed seed() {
		Seed seed = new Seed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		jdbc.update("""
			insert into recorded_commands(command_id,command_type,payload_json,submitted_at,
			 auth_issuer,auth_subject,binding_id,auth_valid_until,auth_external_authorities_json)
			values (?,'TEST','{}',?,'issuer','subject',?,?, '[]'::jsonb)
			""", seed.commandId(), Timestamp.from(T0), UUID.randomUUID(), Timestamp.from(T0.plusSeconds(60)));
		jdbc.update("""
			insert into command_outcomes(command_id,outcome_type,pot_id,resulting_version,public_code,resolved_at)
			values (?,'APPLIED',?,7,null,?)
			""", seed.commandId(), seed.potId(), Timestamp.from(T0));
		jdbc.update("""
			insert into command_terminal_events(event_id,event_type,command_id,command_partition_hash,trace_id,recorded_at)
			values (?,'COMMAND_APPLIED',?,0,null,?)
			""", seed.eventId(), seed.commandId(), Timestamp.from(T0));
		return seed;
	}

	private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Coordination timed out"); }
		catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
	}
	private static void await(CyclicBarrier barrier) {
		try { barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS); }
		catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); throw new AssertionError(error); }
	}

	private record Seed(UUID commandId, UUID eventId, UUID potId) {}
	private record TxInfo(int pid, long xid, String isolation) {}
	private record BlockInfo(int observerPid, String query) {}
	private record Contender(TxProbe probe, Claim claim) {}
	private static final class RollbackMarker extends RuntimeException {}
	private static final class TxProbe {
		final String name;
		final AtomicReference<TxInfo> info = new AtomicReference<>();
		final AtomicInteger completion = new AtomicInteger(-1);
		final CountDownLatch started = new CountDownLatch(1);
		TxProbe(String name) { this.name = name; }
		void awaitStarted() { await(started); }
	}
	private static final class Gate implements AutoCloseable {
		final String name;
		final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
		Gate(String name) { this.name = name; }
		void pause() { System.out.println("BARRIER reached=" + name); reached.countDown(); await(release); }
		void awaitReached() { await(reached); }
		@Override public void close() { release.countDown(); }
	}
	private static final class ControlledClock extends Clock {
		final AtomicReference<Instant> instant;
		final ZoneId zone;
		ControlledClock(AtomicReference<Instant> instant, ZoneId zone) { this.instant = instant; this.zone = zone; }
		void set(Instant now) { instant.set(now); }
		@Override public Instant instant() { return instant.get(); }
		@Override public ZoneId getZone() { return zone; }
		@Override public Clock withZone(ZoneId zone) { return new ControlledClock(instant, zone); }
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class, ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CommandResultRuntimeConfiguration.class, JdbcCommandResultSource.class, JdbcCommandResultStore.class})
	static class TestApplication {}
}
