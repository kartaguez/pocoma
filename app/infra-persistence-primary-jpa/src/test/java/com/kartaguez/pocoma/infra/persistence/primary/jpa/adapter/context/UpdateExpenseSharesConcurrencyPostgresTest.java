package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.write.pot.input.UpdateExpenseDetailsInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.write.pot.exception.PotVersionCasConflictException;
import com.kartaguez.pocoma.engine.write.pot.input.DeleteExpenseInput;
import com.kartaguez.pocoma.engine.write.pot.input.UpdateExpenseSharesInput;
import com.kartaguez.pocoma.engine.write.pot.port.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;
import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;
import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.JpaPotGlobalVersionAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaPotCommandEventAppendAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseHeaderRepository;

/** R03: real JPA reads/writes, two physical transactions, no timing-based scheduling. */
@SpringBootTest(classes = UpdateExpenseSharesConcurrencyPostgresTest.Application.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"})
@Testcontainers
class UpdateExpenseSharesConcurrencyPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("r03").withUsername("pocoma").withPassword("pocoma");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired JpaExpenseContextAdapter contexts;
    @Autowired JpaExpenseHeaderAdapter headers;
    @Autowired JpaPotGlobalVersionAdapter versions;
    @Autowired JpaPotCommandEventAppendAdapter append;
    @MockitoSpyBean JpaExpenseHeaderRepository headerRepository;
    @MockitoSpyBean JpaExpenseSharesAdapter shares;
    private final AtomicReference<Runnable> afterHeader = new AtomicReference<>();
    private final AtomicReference<Runnable> afterHistoricalHeader = new AtomicReference<>();
    private final AtomicReference<Runnable> afterShares = new AtomicReference<>();
    private UUID pot, expense, shareholder, creator, nextCreator;

    @BeforeEach
    void seed() {
        // Each test has its own aggregate IDs; immutable metadata need never be deleted.
        pot = UUID.randomUUID(); expense = UUID.randomUUID();
        shareholder = UUID.randomUUID(); creator = UUID.randomUUID(); nextCreator = UUID.randomUUID();
        doAnswer(call -> {
            // Spring Data uses a JDK proxy: retain MockitoSpyBean's real delegate.
            var result = org.mockito.Mockito.mockingDetails(headerRepository)
                    .getMockCreationSettings().getDefaultAnswer().answer(call);
            runOnce(afterHeader);
            return result;
        }).when(headerRepository).findCurrentActive(any());
        doAnswer(call -> {
            var result = org.mockito.Mockito.mockingDetails(headerRepository)
                    .getMockCreationSettings().getDefaultAnswer().answer(call);
            runOnce(afterHistoricalHeader);
            return result;
        }).when(headerRepository).findActiveAtVersion(any(), anyLong());
        doAnswer(call -> {
            var result = call.callRealMethod();
            runOnce(afterShares);
            return result;
        }).when(shares).loadActiveAtVersion(any(), anyLong());
        jdbc.update("insert into pot_global_versions(pot_id,version) values (?,5)", pot);
        jdbc.update("insert into pot_version_metadata select ?, n, current_timestamp from generate_series(1,5) n", pot);
        jdbc.update("insert into pot_headers(id,pot_id,started_at_version,label,creator_id,deleted) values (?,?,1,'R03',?,false)", UUID.randomUUID(), pot, creator);
        jdbc.update("insert into shareholders(id,shareholder_id,pot_id,started_at_version,name,weight_numerator,weight_denominator,deleted) values (?,?,?,1,'member',1,1,false)", UUID.randomUUID(), shareholder, pot);
        jdbc.update("insert into expense_headers(id,expense_id,pot_id,started_at_version,payer_id,amount_numerator,amount_denominator,label,expense_date,deleted) values (?,?,?,5,?,10,1,'expense',date '2026-10-18',false)", UUID.randomUUID(), expense, pot, shareholder);
        jdbc.update("insert into expense_shares(id,expense_id,shareholder_id,pot_id,started_at_version,weight_numerator,weight_denominator) values (?,?,?,?,5,1,1)", UUID.randomUUID(), expense, shareholder, pot);
    }

    @Test
    void deletedExpenseMustNotAcceptSharesFromMixedContext() throws Exception {
        var gate = new Gate();
        afterHeader.set(gate::pause);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> a = executor.submit(() -> update(6, false));
            try {
                gate.awaitRead(); // A has X@5, but has not read Pot version.
                assertState(5, false, 1, 1, 0);
                delete(); // B uses the production DeleteExpense use case and commits V6.
                assertState(6, true, 1, 1, 1);
            } finally { gate.resume(); }
            var failure = assertThrows(ExecutionException.class, () -> a.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessRuleViolationException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("already deleted"));
        }
        assertState(6, true, 1, 1, 1);
        assertEquals(List.of(1L,2L,3L,4L,5L,6L), jdbc.queryForList(
                "select version from pot_version_metadata where pot_id=? order by version", Long.class, pot));
        assertEquals(0L, number("select count(*) from expense_shares where pot_id=? and (started_at_version>6 or ended_at_version is not null)"));
        assertEquals(0L, number("select count(*) from business_event_outbox where pot_id=? and event_type='EXPENSE_SHARES_UPDATED'"));
        evidence("R03 rejected, no A effects", 6);
    }

    enum Mutation { DELETE, DETAILS, SHARES }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void allContextsRejectDeletionBetweenOwnershipAndVersionRead(Mutation mutation) throws Exception {
        var gate = new Gate();
        afterHeader.set(gate::pause);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> a = executor.submit(() -> mutate(mutation, 6));
            try {
                gate.awaitRead();
                delete();
            } finally { gate.resume(); }
            var failure = assertThrows(ExecutionException.class, () -> a.get(30, TimeUnit.SECONDS));
            assertInstanceOf(BusinessRuleViolationException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("already deleted"));
        }
        assertState(6, true, 1, 1, 1);
        assertEquals(0L, number("select count(*) from pot_version_metadata where pot_id=? and version=7"));
        assertEquals(2L, number("select count(*) from expense_headers where pot_id=?"));
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void laterCommitsPreserveHistoricalContextIncludingAuthorization(Mutation mutation) throws Exception {
        UUID memberAt5 = UUID.randomUUID();
        jdbc.update("update shareholders set user_id=? where pot_id=?", memberAt5, pot);
        var gate = new Gate();
        afterHistoricalHeader.set(gate::pause); // V5 fixed; PotHeader and shareholders still unread.
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> a = executor.submit(() -> assertContext(mutation, 5, false, false, creator, memberAt5));
            try {
                gate.awaitRead();
                delete(true);
            } finally { gate.resume(); }
            a.get(30, TimeUnit.SECONDS);
        }
        assertFalse(headers.loadActiveAtVersion(ExpenseId.of(expense), 5).deleted());
        assertTrue(headers.loadActiveAtVersion(ExpenseId.of(expense), 6).deleted());
        assertContext(mutation, 6, true, true, nextCreator, null);
        var rejected = assertThrows(BusinessRuleViolationException.class, () -> mutate(mutation, 6));
        assertTrue(rejected.getMessage().contains("pot is already deleted"));
        assertEquals(0L, number("select count(*) from pot_version_metadata where pot_id=? and version=7"));
        assertEquals(1L, number("select count(*) from business_event_outbox where pot_id=?"));
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void shareholderReassignmentIsReadAtTheFixedVersion(Mutation mutation) throws Exception {
        UUID oldMember = UUID.randomUUID(), newMember = UUID.randomUUID();
        jdbc.update("update shareholders set user_id=? where pot_id=?", oldMember, pot);
        var gate = new Gate();
        afterHistoricalHeader.set(gate::pause);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> a = executor.submit(() -> assertContext(mutation, 5, false, false, creator, oldMember));
            try {
                gate.awaitRead();
                inOwner(() -> {
                    versions.updateIfActive(new com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion(
                            com.kartaguez.pocoma.domain.pot.value.id.PotId.of(pot), 5),
                            new com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion(
                                    com.kartaguez.pocoma.domain.pot.value.id.PotId.of(pot), 6));
                    jdbc.update("update shareholders set ended_at_version=6 where pot_id=? and ended_at_version is null", pot);
                    jdbc.update("insert into shareholders(id,shareholder_id,pot_id,started_at_version,name,weight_numerator,weight_denominator,user_id,deleted) values (?,?,?,6,'reassigned',1,1,?,false)", UUID.randomUUID(), shareholder, pot, newMember);
                });
            } finally { gate.resume(); }
            a.get(30, TimeUnit.SECONDS);
        }
        assertContext(mutation, 6, false, false, creator, newMember);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void ownershipLookupUsesExpensePotRatherThanAnUnrelatedPot(Mutation mutation) {
        UUID otherPot = UUID.randomUUID();
        jdbc.update("insert into pot_global_versions(pot_id,version) values (?,99)", otherPot);
        jdbc.update("insert into pot_headers(id,pot_id,started_at_version,label,creator_id,deleted) values (?,?,1,'other',?,true)", UUID.randomUUID(), otherPot, UUID.randomUUID());
        assertContext(mutation, 5, false, false, creator, null);
    }

    private void assertContext(Mutation mutation, long version, boolean deleted, boolean potDeleted,
                               UUID expectedCreator, UUID expectedMember) {
        inOwner(() -> {
            assertEquals("read committed", jdbc.queryForObject("show transaction_isolation", String.class));
            var id = ExpenseId.of(expense);
            var sharesContext = switch (mutation) {
                case SHARES -> contexts.loadUpdateExpenseSharesContext(id).orElseThrow();
                case DETAILS -> {
                    var c = contexts.loadUpdateExpenseDetailsContext(id).orElseThrow();
                    yield new com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseSharesContext(
                            c.potGlobalVersion(), c.deleted(), c.potDeleted(), c.creatorId(), c.shareholderIds(), c.shareholderUsers(), java.util.List.of());
                }
                case DELETE -> {
                    var c = contexts.loadDeleteExpenseContext(id).orElseThrow();
                    yield new com.kartaguez.pocoma.engine.write.pot.context.UpdateExpenseSharesContext(
                            c.potGlobalVersion(), c.deleted(), c.potDeleted(), c.creatorId(), Set.of(), c.shareholderUsers(), java.util.List.of());
                }
            };
            assertEquals(pot, sharesContext.potGlobalVersion().potId().value());
            assertEquals(version, sharesContext.potGlobalVersion().version());
            assertEquals(deleted, sharesContext.deleted());
            assertEquals(potDeleted, sharesContext.potDeleted());
            assertEquals(UserId.of(expectedCreator), sharesContext.creatorId());
            assertEquals(expectedMember == null ? java.util.Map.of() : java.util.Map.of(ShareholderId.of(shareholder), UserId.of(expectedMember)), sharesContext.shareholderUsers());
            if (mutation != Mutation.DELETE) assertEquals(potDeleted ? Set.of() : Set.of(ShareholderId.of(shareholder)), sharesContext.shareholderIds());
        });
    }

    private void mutate(Mutation mutation, long expected) {
        if (mutation == Mutation.SHARES) { update(expected, false); return; }
        inOwner(() -> {
            var events = new CapturedEvents();
            if (mutation == Mutation.DELETE) {
                PotBusinessUseCaseFactory.deleteExpense(contexts, headers, versions, events, new PotAuthorizationGuard())
                        .deleteExpense(user(), new DeleteExpenseInput(expense, expected));
            } else {
                PotBusinessUseCaseFactory.updateExpenseDetails(contexts, headers, versions, events, new PotAuthorizationGuard())
                        .updateExpenseDetails(user(), new UpdateExpenseDetailsInput(expense, shareholder, 20, 1,
                                "changed", java.time.LocalDate.parse("2026-10-18"), expected));
            }
            append.appendAll(events.values).events().forEach(owner()::recordPublishedEvent);
        });
    }

    @Test
    void losingPotCasHasNoDurableEffect() throws Exception {
        var gate = new Gate();
        afterShares.set(gate::pause);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> a = executor.submit(() -> update(5, false));
            try {
                gate.awaitRead(); // This time A already owns a coherent V5 context and shares.
                delete();
                assertState(6, true, 1, 1, 1);
            } finally { gate.resume(); }
            var failure = assertThrows(ExecutionException.class, () -> a.get(30, TimeUnit.SECONDS));
            assertInstanceOf(PotVersionCasConflictException.class, failure.getCause());
        }
        assertState(6, true, 1, 1, 1);
        assertEquals(0L, number("select count(*) from pot_version_metadata where pot_id=? and version=7"));
        evidence("CAS lost, no A effects", 6);
    }

    @Test
    void rollbackAfterEventFlushRemovesSharesVersionMetadataAndEvent() {
        assertThrows(DeliberateRollback.class, () -> update(5, true));
        assertState(5, false, 1, 1, 0);
        assertEquals(0L, number("select count(*) from pot_version_metadata where pot_id=? and version=6"));
        evidence("rollback after Event flush, no durable effects", 5);
    }

    private void update(long expected, boolean rollback) {
        inOwner(() -> {
            assertEquals("read committed", jdbc.queryForObject("show transaction_isolation", String.class));
            var events = new CapturedEvents();
            PotBusinessUseCaseFactory.updateExpenseShares(contexts, shares, versions, events, new PotAuthorizationGuard())
                    .updateExpenseShares(user(), new UpdateExpenseSharesInput(expense,
                            Set.of(new UpdateExpenseSharesInput.ExpenseShareInput(shareholder, 2, 1)), expected));
            append.appendAll(events.values).events().forEach(owner()::recordPublishedEvent);
            if (rollback) {
                assertState(6, false, 2, 2, 1); // SQL visible inside A, after actual outbox flush.
                throw new DeliberateRollback();
            }
        });
    }

    private void delete() { delete(false); }

    private void delete(boolean changeRelations) {
        inOwner(() -> {
            var events = new CapturedEvents();
            PotBusinessUseCaseFactory.deleteExpense(contexts, headers, versions, events, new PotAuthorizationGuard())
                    .deleteExpense(user(), new DeleteExpenseInput(expense, 5));
            if (changeRelations) {
                // Fixture for a committed V6 with changed versioned Pot and membership facts.
                jdbc.update("update pot_headers set ended_at_version=6 where pot_id=? and ended_at_version is null", pot);
                jdbc.update("insert into pot_headers(id,pot_id,started_at_version,label,creator_id,deleted) values (?,?,6,'deleted',?,true)", UUID.randomUUID(), pot, nextCreator);
                jdbc.update("update shareholders set ended_at_version=6 where pot_id=? and ended_at_version is null", pot);
                jdbc.update("insert into shareholders(id,shareholder_id,pot_id,started_at_version,name,weight_numerator,weight_denominator,deleted) values (?,?,?,6,'deleted',1,1,true)", UUID.randomUUID(), shareholder, pot);
            }
            append.appendAll(events.values).events().forEach(owner()::recordPublishedEvent);
        });
    }

    private UserContext user() {
        return new UserContext(UserId.of(creator), Set.of(new Permission("EXPENSE", "UPDATE"), new Permission("EXPENSE", "DELETE")));
    }

    private void assertState(long version, boolean deleted, long rows, long weight, long events) {
        assertEquals(version, number("select version from pot_global_versions where pot_id=?"));
        assertEquals(version, number("select count(*) from pot_version_metadata where pot_id=?"));
        assertEquals(deleted, jdbc.queryForObject("select deleted from expense_headers where pot_id=? and ended_at_version is null", Boolean.class, pot));
        assertEquals(rows, number("select count(*) from expense_shares where pot_id=?"));
        assertEquals(1L, number("select count(*) from expense_shares where pot_id=? and ended_at_version is null"));
        assertEquals(weight, number("select weight_numerator from expense_shares where pot_id=? and ended_at_version is null"));
        assertEquals(events, number("select count(*) from business_event_outbox where pot_id=?"));
    }

    private long number(String sql) { return jdbc.queryForObject(sql, Long.class, pot); }
    private void evidence(String phase, long version) {
        System.out.printf("R03 %s pot=%s version=%d metadata=%s headers=%s shares=%s events=%s%n", phase, pot, version,
                jdbc.queryForList("select version from pot_version_metadata where pot_id=? order by version", pot),
                jdbc.queryForList("select started_at_version,ended_at_version,deleted from expense_headers where pot_id=? order by started_at_version", pot),
                jdbc.queryForList("select started_at_version,ended_at_version,weight_numerator,weight_denominator from expense_shares where pot_id=? order by started_at_version", pot),
                jdbc.queryForList("select event_type,version from business_event_outbox where pot_id=? order by version", pot));
    }
    private static void runOnce(AtomicReference<Runnable> hook) {
        var action = hook.getAndSet(null);
        if (action != null) action.run();
    }
    private static final class CapturedEvents implements EventPublisherPort {
        final List<BusinessEvent> values = new ArrayList<>();
        @Override public void publish(ExpenseDeletedEvent event) { values.add(event); }
        @Override public void publish(com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent event) { values.add(event); }
        @Override public void publish(ExpenseSharesUpdatedEvent event) { values.add(event); }
    }
    private static final class DeliberateRollback extends RuntimeException { }
    private static final class Gate {
        final CountDownLatch read = new CountDownLatch(1), proceed = new CountDownLatch(1);
        void pause() { read.countDown(); await(proceed); }
        void awaitRead() { await(read); }
        void resume() { proceed.countDown(); }
        private static void await(CountDownLatch latch) {
            try { assertTrue(latch.await(30, TimeUnit.SECONDS), "barrier timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
    @EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
    @Import({JpaExpenseContextAdapter.class, JpaExpenseHeaderAdapter.class, JpaExpenseSharesAdapter.class,
            JpaPotGlobalVersionAdapter.class, JpaPotCommandEventAppendAdapter.class})
    static class Application {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }

    private com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner owner() {
        var primary=(org.springframework.orm.jpa.JpaTransactionManager)manager;
        return new com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner(primary,
                new com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.JdbcEventTransactionSequencer(primary));
    }
    private void inOwner(Runnable action) {owner().runInTransaction(()->{action.run();return null;});}
}
