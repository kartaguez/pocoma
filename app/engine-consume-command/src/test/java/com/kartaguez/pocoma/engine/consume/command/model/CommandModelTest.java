package com.kartaguez.pocoma.engine.consume.command.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;

class CommandModelTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final CommandAppliedResult APPLIED = new CommandAppliedResult(UUID.randomUUID(), 7);

	@Test
	void recordsGenericImmutableCommandAndAuthenticationEvidence() {
		Set<String> authorities = new HashSet<>(Set.of("scope:pot:create"));
		TargetCommandEnvelope envelope = new TargetCommandEnvelope(
				new ExternalIdentity("https://issuer.example", "subject-42"),
				new BindingId(UUID.randomUUID()),
				new CommandAuthenticationEvidence(authorities, NOW.plusSeconds(60)));
		RecordedCommand command = new RecordedCommand(
				new CommandId(UUID.randomUUID()), new CommandType("POT_CREATE_V1"), "{}", NOW, envelope);
		authorities.clear();
		assertEquals(Set.of("scope:pot:create"), command.envelope().authenticationEvidence().externalAuthorities());
		assertThrows(UnsupportedOperationException.class,
				() -> command.envelope().authenticationEvidence().externalAuthorities().add("other"));
	}

	@Test
	void validatesValueObjectsAndRequiredEnvelopeFields() {
		assertThrows(NullPointerException.class, () -> new CommandId(null));
		assertThrows(NullPointerException.class, () -> new PocomaUserId(null));
		assertThrows(NullPointerException.class, () -> new CommandType(null));
		assertThrows(IllegalArgumentException.class, () -> new CommandType(" "));
		assertThrows(NullPointerException.class, () -> new RecordedCommand(
				new CommandId(UUID.randomUUID()), new CommandType("TYPE_V1"), null, NOW, envelope()));
		assertEquals("", new RecordedCommand(new CommandId(UUID.randomUUID()),
				new CommandType("TYPE_V1"), "", NOW, envelope()).serializedPayload());
		assertEquals(" ", new RecordedCommand(new CommandId(UUID.randomUUID()),
				new CommandType("TYPE_V1"), " ", NOW, envelope()).serializedPayload());
	}

	@Test
	void targetEnvelopeRetainsOnlyExternalIdentityBindingAndMinimalAuthenticationEvidence() {
		Set<String> authorities = new HashSet<>(Set.of("scope:pot:create"));
		TargetCommandEnvelope envelope = new TargetCommandEnvelope(
				new ExternalIdentity("https://issuer.example", "subject-42"),
				new BindingId(UUID.randomUUID()),
				new CommandAuthenticationEvidence(authorities, NOW.plusSeconds(60)));
		authorities.clear();

		RecordedCommand command = new RecordedCommand(new CommandId(UUID.randomUUID()),
				new CommandType("POT_CREATE_V1"), "{}", NOW, envelope);

		assertSame(envelope, command.envelope());
		assertEquals(Set.of("scope:pot:create"), envelope.authenticationEvidence().externalAuthorities());
	}

	@Test
	void validatesProvenanceArtifactsAndTypedEventResults() {
		CommandExecutionInput subject = new CommandExecutionInput("POT", "pot-1", 7);
		CommandExecutionArtifact artifact = new CommandExecutionArtifact(
				"EVENT", "PotUpdated", "event-1", OptionalLong.empty(), Optional.of(subject), NOW);
		TestBusinessEvent event = new TestBusinessEvent("updated");
		List<BusinessEvent> mutableEvents = new ArrayList<>(List.of(event));
		CommandUseCaseResult.Succeeded succeeded = new CommandUseCaseResult.Succeeded(
				List.of(subject), APPLIED, mutableEvents);
		mutableEvents.clear();

		assertSame(event, succeeded.events().getFirst());
		assertThrows(UnsupportedOperationException.class,
				() -> succeeded.events().add(new TestBusinessEvent("deleted")));
		assertThrows(NullPointerException.class,
				() -> new CommandUseCaseResult.Succeeded(
						List.of(), APPLIED, Collections.singletonList(null)));
		assertEquals(Optional.of(subject), artifact.subject());
		assertThrows(IllegalArgumentException.class, () -> new CommandExecutionInput("POT", "pot-1", 0));
		assertThrows(IllegalArgumentException.class, () -> new CommandExecutionArtifact(
				"EVENT", "TYPE", "id", OptionalLong.of(0), Optional.empty(), NOW));
	}

	private static TargetCommandEnvelope envelope() {
		return new TargetCommandEnvelope(new ExternalIdentity("issuer", "subject"),
				new BindingId(UUID.randomUUID()), new CommandAuthenticationEvidence(Set.of(), NOW.plusSeconds(60)));
	}

	private record TestBusinessEvent(String change) implements BusinessEvent {
		@Override public EventType eventType() { return new EventType("TEST_EVENT"); }
	}
}
