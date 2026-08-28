package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDeletedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.domain.pot.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

class BusinessEventRecordMapperTest {

	private final BusinessEventRecordMapper mapper = new BusinessEventRecordMapper(new ObjectMapper());

	@Test
	void roundTripsEverySupportedTypedEventAndItsMetadata() {
		PotId potId = PotId.of(UUID.randomUUID());
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		Set<ShareholderId> shareholderIds = Set.of(
				ShareholderId.of(UUID.randomUUID()), ShareholderId.of(UUID.randomUUID()));
		List<BusinessEvent> events = List.of(
				new PotCreatedEvent(potId, 7),
				new PotDeletedEvent(potId, 7),
				new PotDetailsUpdatedEvent(potId, 7),
				new PotShareholdersAddedEvent(potId, shareholderIds, 7),
				new PotShareholdersDetailsUpdatedEvent(potId, shareholderIds, 7),
				new PotShareholdersWeightsUpdatedEvent(potId, shareholderIds, 7),
				new ExpenseCreatedEvent(expenseId, potId, 7),
				new ExpenseDeletedEvent(expenseId, potId, 7),
				new ExpenseDetailsUpdatedEvent(expenseId, potId, 7),
				new ExpenseSharesUpdatedEvent(expenseId, potId, 7));

		for (BusinessEvent event : events) {
			RecordedEvent<BusinessEvent> expected = new RecordedEvent<>(
					UUID.randomUUID(), event, Instant.parse("2026-08-28T07:00:00Z"),
					EventTraceMetadata.of("trace-1", 42L));
			BusinessEventEnvelope envelope = mapper.toEnvelope(expected);

			assertEquals(event.getClass().getSimpleName(), envelope.eventType());
			assertEquals(expected, mapper.toRecordedEvent(envelope));
		}
	}

	@Test
	void readsLegacyShareholderPayloadWithoutThePreviouslyMissingIdentifiers() {
		UUID eventId = UUID.randomUUID();
		PotId potId = PotId.of(UUID.randomUUID());
		BusinessEventEnvelope legacy = new BusinessEventEnvelope(
				eventId,
				"PotShareholdersAddedEvent",
				potId,
				potId.value(),
				2,
				"{\"eventType\":\"PotShareholdersAddedEvent\",\"version\":2}",
				null,
				null,
				Instant.parse("2026-08-28T07:00:00Z"));

		var recorded = mapper.toRecordedEvent(legacy);

		assertEquals(new PotShareholdersAddedEvent(potId, Set.of(), 2), recorded.event());
	}
}
