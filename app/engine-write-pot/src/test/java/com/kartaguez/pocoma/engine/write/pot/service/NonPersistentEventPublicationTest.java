package com.kartaguez.pocoma.engine.write.pot.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.aggregate.*;
import com.kartaguez.pocoma.domain.pot.event.*;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.write.pot.context.CreateExpenseContext;
import com.kartaguez.pocoma.engine.write.pot.input.*;
import com.kartaguez.pocoma.engine.write.pot.port.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.*;
import com.kartaguez.pocoma.engine.write.pot.security.UserContext;

/** Actual business factories, only in-memory collaborators: no Event store or transaction protocol. */
class NonPersistentEventPublicationTest {
    @Test void potAndExpensePublishTypedEventsWithoutPersistenceOrStorageIdentities() {
        var publisher = new MemoryPublisher();
        var user = UserId.of(UUID.randomUUID());
        var versions = new MemoryVersions();
        var savedPots = new ArrayList<PotHeader>();
        var pot = PotBusinessUseCaseFactory.createPot(versions, new PotHeaderPort() {
            @Override public void saveNew(PotHeader value, long version) { savedPots.add(value); }
        }, publisher, new CreatePotAuthorizationPolicy()).createPot(
                new UserContext(user, Set.of(new Permission("POT", "CREATE"))),
                new CreatePotInput("Trip", user.value()));
        assertEquals(List.of(new PotCreatedEvent(pot.id(), 1)), publisher.events);
        assertEquals(pot.id(), savedPots.getFirst().id());

        var shareholder = ShareholderId.of(UUID.randomUUID());
        var savedExpenses = new ArrayList<ExpenseHeader>();
        var savedShares = new ArrayList<ExpenseShares>();
        var expense = PotBusinessUseCaseFactory.createExpense(new PotContextPort() {
            @Override public Optional<CreateExpenseContext> loadCreateExpenseContext(PotId id) {
                return Optional.of(new CreateExpenseContext(new PotGlobalVersion(pot.id(), 1), false,
                        user, Set.of(shareholder)));
            }
        }, versions, new ExpenseHeaderPort() {
            @Override public void saveNew(ExpenseHeader value, long version) { savedExpenses.add(value); }
        }, new ExpenseSharesPort() {
            @Override public void saveNew(ExpenseId id, ExpenseShares value, long version) { savedShares.add(value); }
        }, publisher, new PotAuthorizationGuard()).createExpense(
                new UserContext(user, Set.of(new Permission("EXPENSE", "CREATE"))),
                new CreateExpenseInput(pot.id().value(), shareholder.value(), 42, 1, "Dinner", LocalDate.of(2026, 1, 1),
                        Set.of(new CreateExpenseInput.ExpenseShareInput(shareholder.value(), 1, 1)), 1));
        assertEquals(List.of(new PotCreatedEvent(pot.id(), 1),
                new ExpenseCreatedEvent(expense.expenseId(), pot.id(), 2)), publisher.events);
        assertEquals(expense.expenseId(), savedExpenses.getFirst().id());
        assertEquals(1, savedShares.size());
        assertEquals(List.of(new PotGlobalVersion(pot.id(), 1), new PotGlobalVersion(pot.id(), 2)), versions.accepted);
        assertEquals(Set.of("potId", "version"), components(PotCreatedEvent.class));
        assertEquals(Set.of("expenseId", "potId", "version"), components(ExpenseCreatedEvent.class));
    }
    private static Set<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }
    static final class MemoryPublisher implements EventPublisherPort {
        final List<BusinessEvent> events = new ArrayList<>();
        @Override public void publish(PotCreatedEvent event) { events.add(event); }
        @Override public void publish(ExpenseCreatedEvent event) { events.add(event); }
    }
    static final class MemoryVersions implements PotGlobalVersionPort {
        final List<PotGlobalVersion> accepted = new ArrayList<>();
        @Override public void save(PotGlobalVersion version) { accepted.add(version); }
        @Override public void updateIfActive(PotGlobalVersion expected, PotGlobalVersion next) {
            assertEquals(accepted.getLast(), expected);
            accepted.add(next);
        }
    }
}
