package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaPotShareholdersAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.JpaPotGlobalVersionAdapter;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.core.JpaExpenseShareRepository;

/** Actual PostgreSQL predicates and full replacement writer, without mocked temporal reads. */
@SpringBootTest(classes = CommandObsolescencePostgresTest.Application.class, properties = {
    "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration"})
@Testcontainers
class CommandObsolescencePostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired JpaPotContextAdapter pots;
    @Autowired JpaExpenseContextAdapter expenses;
    @Autowired JpaExpenseSharesAdapter shares;
    @Autowired JpaExpenseHeaderAdapter headers;
    @Autowired JpaPotShareholdersAdapter shareholders;
    @Autowired JpaPotGlobalVersionAdapter versions;
    @Autowired JpaExpenseShareRepository shareRows;
    @Autowired PlatformTransactionManager manager;
    private UUID pot, expense, a, b, creator, member;

    @BeforeEach void seed() {
        pot = UUID.randomUUID(); expense = UUID.randomUUID(); a = UUID.randomUUID(); b = UUID.randomUUID();
        creator = UUID.randomUUID(); member = UUID.randomUUID();
        jdbc.update("insert into pot_global_versions(pot_id,version) values (?,8)", pot);
        jdbc.update("insert into pot_headers(id,pot_id,started_at_version,label,creator_id,deleted) values (?,?,1,'Pot',?,false)", UUID.randomUUID(), pot, creator);
        shareholder(a, 1, member); shareholder(b, 7, null);
        header(2, false); share(a, 2, pot); share(b, 2, pot);
    }

    @Test void targetedShareholdersAndConservativeDetailsWeightsUseTheirOwnStarts() {
        var details = pots.loadUpdatePotShareholdersDetailsContext(potId()).orElseThrow();
        var weights = pots.loadUpdatePotShareholdersWeightsContext(potId()).orElseThrow();
        assertEquals(Map.of(id(a), 1L, id(b), 7L), details.shareholderStartedAtVersions());
        details.assertUpdatePreconditions(2, Set.of(id(a)));
        weights.assertUpdatePreconditions(2, Set.of(id(a)));
        stale(() -> details.assertUpdatePreconditions(2, Set.of(id(a), id(b))));
        stale(() -> weights.assertUpdatePreconditions(2, Set.of(id(b))));
        pots.loadUpdatePotDetailsContext(potId()).orElseThrow().assertUpdatePreconditions(2);
        stale(() -> pots.loadDeletePotContext(potId()).orElseThrow().assertDeletePreconditions(7));
    }

    @Test void creationsValidateReferencesAndAuthorizationAtV() {
        pots.loadAddPotShareholdersContext(potId()).orElseThrow().assertAddPreconditions(1);
        var context = pots.loadCreateExpenseContext(potId()).orElseThrow();
        context.assertCreatePreconditions(1, id(b), Set.of(id(b))); // b created after O, valid at V.
        assertEquals(Map.of(id(a), UserId.of(member)), context.shareholderUsers());
        assertEquals(8, context.potGlobalVersion().version());
        rule("SHAREHOLDER_NOT_PRESENT", () -> context.assertCreatePreconditions(1, id(UUID.randomUUID()), Set.of(id(a))));
    }

    @Test void fullReplacementRecreatesSurvivorAndDetectsRemovedBeneficiary() {
        replace(Set.of(id(b)));
        var context = expenses.loadUpdateExpenseSharesContext(expenseId()).orElseThrow();
        assertEquals(List.of(9L), context.shareStartedAtVersions());
        stale(() -> context.assertUpdatePreconditions(8, Set.of(id(b))));
        stale(() -> expenses.loadDeleteExpenseContext(expenseId()).orElseThrow().assertDeletePreconditions(8));
        expenses.loadUpdateExpenseDetailsContext(expenseId()).orElseThrow().assertUpdatePreconditions(2, id(a));
        assertEquals(2, expenses.loadUpdateExpenseDetailsContext(expenseId()).orElseThrow().headerStartedAtVersion());
        assertEquals(2, shareRows.findActiveAtVersion(expense, 8).size());
        assertEquals(Set.of(b), shareRows.findActiveAtVersion(expense, 9).stream().map(row -> row.shareholderId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, jdbc.queryForObject("select count(*) from expense_shares where expense_id=? and ended_at_version=9", Integer.class, expense));
    }

    @Test void headerChangeLeavesSharesCompatibleButInvalidatesDetailsAndDelete() {
        jdbc.update("update expense_headers set ended_at_version=9 where expense_id=?", expense);
        header(9, false);
        jdbc.update("update pot_global_versions set version=9 where pot_id=?", pot);
        expenses.loadUpdateExpenseSharesContext(expenseId()).orElseThrow().assertUpdatePreconditions(2, Set.of(id(a)));
        stale(() -> expenses.loadUpdateExpenseDetailsContext(expenseId()).orElseThrow().assertUpdatePreconditions(8, id(a)));
        stale(() -> expenses.loadDeleteExpenseContext(expenseId()).orElseThrow().assertDeletePreconditions(8));
    }

    @Test void emptySetRequiresExactObservationRatherThanTheOldHeader() {
        replace(Set.of());
        var context = expenses.loadUpdateExpenseSharesContext(expenseId()).orElseThrow();
        assertTrue(context.shareStartedAtVersions().isEmpty());
        stale(() -> context.assertUpdatePreconditions(8, Set.of(id(a))));
        stale(() -> expenses.loadDeleteExpenseContext(expenseId()).orElseThrow().assertDeletePreconditions(8));
        context.assertUpdatePreconditions(9, Set.of(id(a)));
        expenses.loadDeleteExpenseContext(expenseId()).orElseThrow().assertDeletePreconditions(9);
    }

    @Test void contextsReadClosedRowsAtVInsteadOfOpenRows() {
        jdbc.update("update expense_headers set ended_at_version=9 where expense_id=?", expense);
        header(9, true);
        jdbc.update("update pot_headers set ended_at_version=9 where pot_id=?", pot);
        jdbc.update("insert into pot_headers(id,pot_id,started_at_version,label,creator_id,deleted) values (?,?,9,'Next',?,true)", UUID.randomUUID(), pot, UUID.randomUUID());
        jdbc.update("update shareholders set ended_at_version=9 where shareholder_id=?", a);
        shareholder(a, 9, UUID.randomUUID());
        jdbc.update("update expense_shares set ended_at_version=9 where expense_id=?", expense);
        share(b, 9, pot);
        // Deliberately retain V=8 to prove the interval predicate of each repository.
        var context = expenses.loadUpdateExpenseSharesContext(expenseId()).orElseThrow();
        assertEquals(8, context.potGlobalVersion().version());
        assertFalse(context.deleted()); assertFalse(context.potDeleted());
        assertEquals(UserId.of(creator), context.creatorId());
        assertEquals(Map.of(id(a), UserId.of(member)), context.shareholderUsers());
        assertEquals(List.of(2L, 2L), context.shareStartedAtVersions());
        assertEquals(2, expenses.loadUpdateExpenseDetailsContext(expenseId()).orElseThrow().headerStartedAtVersion());
        assertEquals(1, pots.loadUpdatePotDetailsContext(potId()).orElseThrow().headerStartedAtVersion());
        assertEquals(1L, pots.loadUpdatePotShareholdersWeightsContext(potId()).orElseThrow().shareholderStartedAtVersions().get(id(a)));
        context.assertUpdatePreconditions(2, Set.of(id(a)));
    }

    @Test void deletedAndAbsentObjectsKeepTheirDiagnostics() {
        jdbc.update("update expense_headers set ended_at_version=9 where expense_id=?", expense);
        header(9, true);
        jdbc.update("update pot_global_versions set version=9 where pot_id=?", pot);
        rule("EXPENSE_ALREADY_DELETED", () -> expenses.loadDeleteExpenseContext(expenseId()).orElseThrow().assertDeletePreconditions(1));
        assertTrue(expenses.loadDeleteExpenseContext(ExpenseId.of(UUID.randomUUID())).isEmpty());
        jdbc.update("update shareholders set deleted=true where shareholder_id=?", b);
        rule("SHAREHOLDER_NOT_PRESENT", () -> pots.loadUpdatePotShareholdersWeightsContext(potId()).orElseThrow().assertUpdatePreconditions(9, Set.of(id(b))));
    }

    @Test void corruptShareOwnershipOrDuplicateIdentityIsNeverCompatible() {
        share(UUID.randomUUID(), 2, UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> expenses.loadUpdateExpenseSharesContext(expenseId()).orElseThrow());
        jdbc.update("delete from expense_shares where expense_id=? and pot_id<>?", expense, pot);
        share(a, 2, pot);
        assertThrows(IllegalStateException.class, () -> expenses.loadDeleteExpenseContext(expenseId()).orElseThrow());
    }

    @Test void creationsCannotReuseAnExistingOrHistoricalIdentity() {
        var header = headers.loadActiveAtVersion(expenseId(), 8);
        assertThrows(IllegalStateException.class, () -> headers.saveNew(header, 9));
        jdbc.update("update expense_headers set ended_at_version=3,deleted=true where expense_id=?", expense);
        assertThrows(IllegalStateException.class, () -> headers.saveNew(header, 9));
        assertEquals(1, jdbc.queryForObject("select count(*) from expense_headers where expense_id=?", Integer.class, expense));
        assertThrows(IllegalStateException.class, () -> versions.save(new PotGlobalVersion(potId(), 1)));
        assertEquals(8L, jdbc.queryForObject("select version from pot_global_versions where pot_id=?", Long.class, pot));
    }

    @Test void addingShareholdersChecksHistoricalIdentitiesBeforeInserting() {
        var aggregate = new PotShareholders(potId(), Set.of());
        var generated = aggregate.addShareholder(Name.of("new"), Weight.of(Fraction.of(1, 1)), null);
        shareholder(generated.id().value(), 1, null);
        jdbc.update("update shareholders set ended_at_version=2,deleted=true where shareholder_id=?", generated.id().value());
        assertThrows(IllegalStateException.class, () -> shareholders.save(aggregate,
            new PotGlobalVersion(potId(), 8), new PotGlobalVersion(potId(), 9)));
        assertEquals(1, jdbc.queryForObject("select count(*) from shareholders where shareholder_id=?", Integer.class, generated.id().value()));
    }

    private void replace(Set<ShareholderId> beneficiaries) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var next = ExpenseShares.reconstitute(potId(), beneficiaries.stream()
                .map(id -> new ExpenseShare(expenseId(), id, Weight.of(Fraction.of(1, 1))))
                .collect(java.util.stream.Collectors.toSet()));
            shares.save(expenseId(), next, new PotGlobalVersion(potId(), 8), new PotGlobalVersion(potId(), 9));
            jdbc.update("update pot_global_versions set version=9 where pot_id=?", pot);
        });
    }
    private void shareholder(UUID id, long start, UUID user) {
        jdbc.update("insert into shareholders(id,shareholder_id,pot_id,started_at_version,name,user_id,weight_numerator,weight_denominator,deleted) values (?,?,?,?,'member',?,1,1,false)", UUID.randomUUID(), id, pot, start, user);
    }
    private void header(long start, boolean deleted) {
        jdbc.update("insert into expense_headers(id,expense_id,pot_id,started_at_version,payer_id,amount_numerator,amount_denominator,label,expense_date,deleted) values (?,?,?,?,?,10,1,'expense',date '2026-10-18',?)", UUID.randomUUID(), expense, pot, start, a, deleted);
    }
    private void share(UUID shareholder, long start, UUID owner) {
        jdbc.update("insert into expense_shares(id,expense_id,shareholder_id,pot_id,started_at_version,weight_numerator,weight_denominator) values (?,?,?,?,?,1,1)", UUID.randomUUID(), expense, shareholder, owner, start);
    }
    private PotId potId() { return PotId.of(pot); }
    private ExpenseId expenseId() { return ExpenseId.of(expense); }
    private ShareholderId id(UUID value) { return ShareholderId.of(value); }
    private static void stale(Runnable action) { rule("OBSOLETE_COMMAND", action); }
    private static void rule(String code, Runnable action) {
        assertEquals(code, assertThrows(BusinessRuleViolationException.class, action::run).ruleCode());
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EntityScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
    @EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
    @Import({JpaPotContextAdapter.class, JpaExpenseContextAdapter.class, JpaExpenseSharesAdapter.class, JpaExpenseHeaderAdapter.class,
        JpaPotShareholdersAdapter.class, JpaPotGlobalVersionAdapter.class})
    static class Application { }
}
