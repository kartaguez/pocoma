package com.kartaguez.pocoma.domain.pot.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class BusinessEventContractTest {

	private final PotId potId = PotId.of(UUID.randomUUID());
	private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
	private final ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

	@Test
	void everyPotEventExposesItsPotAndPositiveVersionThroughTheDomainContract() {
		List<BusinessEvent> events = List.of(
				new PotCreatedEvent(potId, 3),
				new PotDeletedEvent(potId, 3),
				new PotDetailsUpdatedEvent(potId, 3),
				new PotShareholdersAddedEvent(potId, Set.of(shareholderId), 3),
				new PotShareholdersDetailsUpdatedEvent(potId, Set.of(shareholderId), 3),
				new PotShareholdersWeightsUpdatedEvent(potId, Set.of(shareholderId), 3),
				new ExpenseCreatedEvent(expenseId, potId, 3),
				new ExpenseDeletedEvent(expenseId, potId, 3),
				new ExpenseDetailsUpdatedEvent(expenseId, potId, 3),
				new ExpenseSharesUpdatedEvent(expenseId, potId, 3));

		events.forEach(event -> {
			assertEquals(potId, event.potId());
			assertEquals(3, event.version());
		});
	}

	@Test
	void everyEventRejectsANonPositiveVersion() {
		List<Runnable> invalidConstructions = List.of(
				() -> new PotCreatedEvent(potId, 0),
				() -> new PotDeletedEvent(potId, 0),
				() -> new PotDetailsUpdatedEvent(potId, 0),
				() -> new PotShareholdersAddedEvent(potId, Set.of(shareholderId), 0),
				() -> new PotShareholdersDetailsUpdatedEvent(potId, Set.of(shareholderId), 0),
				() -> new PotShareholdersWeightsUpdatedEvent(potId, Set.of(shareholderId), 0),
				() -> new ExpenseCreatedEvent(expenseId, potId, 0),
				() -> new ExpenseDeletedEvent(expenseId, potId, 0),
				() -> new ExpenseDetailsUpdatedEvent(expenseId, potId, 0),
				() -> new ExpenseSharesUpdatedEvent(expenseId, potId, 0));

		invalidConstructions.forEach(construction ->
				assertThrows(IllegalArgumentException.class, construction::run));
	}

	@Test
	void shareholderEventsDefensivelyCopyTheirIdentifiers() {
		Set<ShareholderId> mutableIds = new HashSet<>();
		mutableIds.add(shareholderId);
		List<Set<ShareholderId>> eventIds = List.of(
				new PotShareholdersAddedEvent(potId, mutableIds, 1).shareholderIds(),
				new PotShareholdersDetailsUpdatedEvent(potId, mutableIds, 1).shareholderIds(),
				new PotShareholdersWeightsUpdatedEvent(potId, mutableIds, 1).shareholderIds());

		mutableIds.clear();

		eventIds.forEach(ids -> {
			assertEquals(Set.of(shareholderId), ids);
			assertThrows(UnsupportedOperationException.class, ids::clear);
		});
	}
}
