package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.EventTraceMetadata;
import com.kartaguez.pocoma.engine.port.in.taskcreation.input.RecordedEvent;
import com.kartaguez.pocoma.engine.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

/** Maps the legacy JSON outbox representation at the infrastructure boundary only. */
public final class BusinessEventRecordMapper {

	private final ObjectMapper objectMapper;

	public BusinessEventRecordMapper(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public RecordedEvent<? extends BusinessEvent> toRecordedEvent(BusinessEventEnvelope envelope) {
		Objects.requireNonNull(envelope, "envelope must not be null");
		JsonNode payload = readPayload(envelope.payloadJson());
		BusinessEvent event = eventFrom(envelope, payload);
		return new RecordedEvent<>(
				envelope.id(),
				event,
				envelope.createdAt(),
				EventTraceMetadata.of(envelope.traceId(), envelope.commandCommittedAtNanos()));
	}

	public BusinessEventEnvelope toEnvelope(RecordedEvent<? extends BusinessEvent> recordedEvent) {
		Objects.requireNonNull(recordedEvent, "recordedEvent must not be null");
		EventProjection projection = EventProjection.from(recordedEvent.event());
		return new BusinessEventEnvelope(
				recordedEvent.eventId(),
				recordedEvent.event().getClass().getSimpleName(),
				projection.potId(),
				projection.aggregateId(),
				projection.version(),
				writePayload(recordedEvent.event(), projection),
				recordedEvent.traceMetadata().traceId().orElse(null),
				recordedEvent.traceMetadata().commandCommittedAtNanos().orElse(null),
				recordedEvent.recordedAt());
	}

	private BusinessEvent eventFrom(BusinessEventEnvelope envelope, JsonNode payload) {
		return switch (envelope.eventType()) {
			case "PotCreatedEvent" -> new PotCreatedEvent(envelope.potId(), envelope.version());
			case "PotDeletedEvent" -> new PotDeletedEvent(envelope.potId(), envelope.version());
			case "PotDetailsUpdatedEvent" -> new PotDetailsUpdatedEvent(envelope.potId(), envelope.version());
			case "PotShareholdersAddedEvent" -> new PotShareholdersAddedEvent(
					envelope.potId(), shareholderIds(payload), envelope.version());
			case "PotShareholdersDetailsUpdatedEvent" -> new PotShareholdersDetailsUpdatedEvent(
					envelope.potId(), shareholderIds(payload), envelope.version());
			case "PotShareholdersWeightsUpdatedEvent" -> new PotShareholdersWeightsUpdatedEvent(
					envelope.potId(), shareholderIds(payload), envelope.version());
			case "ExpenseCreatedEvent" -> new ExpenseCreatedEvent(
					ExpenseId.of(envelope.aggregateId()), envelope.potId(), envelope.version());
			case "ExpenseDeletedEvent" -> new ExpenseDeletedEvent(
					ExpenseId.of(envelope.aggregateId()), envelope.potId(), envelope.version());
			case "ExpenseDetailsUpdatedEvent" -> new ExpenseDetailsUpdatedEvent(
					ExpenseId.of(envelope.aggregateId()), envelope.potId(), envelope.version());
			case "ExpenseSharesUpdatedEvent" -> new ExpenseSharesUpdatedEvent(
					ExpenseId.of(envelope.aggregateId()), envelope.potId(), envelope.version());
			default -> throw new IllegalArgumentException("Unsupported business event type: " + envelope.eventType());
		};
	}

	private String writePayload(BusinessEvent event, EventProjection projection) {
		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("eventType", event.getClass().getSimpleName());
		payload.put("potId", projection.potId().value().toString());
		payload.put("aggregateId", projection.aggregateId().toString());
		payload.put("version", projection.version());
		Set<ShareholderId> shareholderIds = shareholderIds(event);
		if (shareholderIds != null) {
			ArrayNode array = payload.putArray("shareholderIds");
			shareholderIds.stream().map(id -> id.value().toString()).sorted().forEach(array::add);
		}
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Could not serialize business event", exception);
		}
	}

	private JsonNode readPayload(String payloadJson) {
		try {
			return objectMapper.readTree(payloadJson);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Invalid business event payload", exception);
		}
	}

	private static Set<ShareholderId> shareholderIds(JsonNode payload) {
		JsonNode node = payload.path("shareholderIds");
		if (!node.isArray()) {
			return Set.of();
		}
		Set<ShareholderId> ids = new LinkedHashSet<>();
		node.forEach(value -> ids.add(ShareholderId.of(UUID.fromString(value.asText()))));
		return Set.copyOf(ids);
	}

	private static Set<ShareholderId> shareholderIds(BusinessEvent event) {
		return switch (event) {
			case PotShareholdersAddedEvent typed -> typed.shareholderIds();
			case PotShareholdersDetailsUpdatedEvent typed -> typed.shareholderIds();
			case PotShareholdersWeightsUpdatedEvent typed -> typed.shareholderIds();
			default -> null;
		};
	}

	private record EventProjection(PotId potId, UUID aggregateId, long version) {

		private static EventProjection from(BusinessEvent event) {
			return switch (event) {
				case ExpenseCreatedEvent typed -> expense(typed.expenseId(), typed.potId(), typed.version());
				case ExpenseDeletedEvent typed -> expense(typed.expenseId(), typed.potId(), typed.version());
				case ExpenseDetailsUpdatedEvent typed -> expense(typed.expenseId(), typed.potId(), typed.version());
				case ExpenseSharesUpdatedEvent typed -> expense(typed.expenseId(), typed.potId(), typed.version());
				case PotCreatedEvent typed -> pot(typed.potId(), typed.version());
				case PotDeletedEvent typed -> pot(typed.potId(), typed.version());
				case PotDetailsUpdatedEvent typed -> pot(typed.potId(), typed.version());
				case PotShareholdersAddedEvent typed -> pot(typed.potId(), typed.version());
				case PotShareholdersDetailsUpdatedEvent typed -> pot(typed.potId(), typed.version());
				case PotShareholdersWeightsUpdatedEvent typed -> pot(typed.potId(), typed.version());
				default -> throw new IllegalArgumentException(
						"Unsupported business event: " + event.getClass().getName());
			};
		}

		private static EventProjection expense(ExpenseId expenseId, PotId potId, long version) {
			return new EventProjection(potId, expenseId.value(), version);
		}

		private static EventProjection pot(PotId potId, long version) {
			return new EventProjection(potId, potId.value(), version);
		}
	}
}
