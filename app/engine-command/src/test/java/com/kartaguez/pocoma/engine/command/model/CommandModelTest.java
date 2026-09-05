package com.kartaguez.pocoma.engine.command.model;

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
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;

class CommandModelTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void recordsGenericImmutableCommandAndAuthorizationData() {
		Set<Permission> permissions = new HashSet<>();
		permissions.add(new Permission("POT", "CREATE"));
		AuthorizationSnapshot authorization = new AuthorizationSnapshot(
				new PocomaUserId(UUID.randomUUID()), permissions, NOW.minusSeconds(10), NOW.minusSeconds(5),
				NOW.plusSeconds(60), "pocoma-auth");
		permissions.clear();

		RecordedCommand command = new RecordedCommand(
				new CommandId(UUID.randomUUID()), new CommandType("POT_CREATE_V1"), "{}", NOW, authorization);

		assertEquals(Set.of(new Permission("POT", "CREATE")), command.authorization().permissions());
		assertThrows(UnsupportedOperationException.class,
				() -> command.authorization().permissions().add(new Permission("POT", "DELETE")));
	}

	@Test
	void validatesValueObjectsAndRequiredEnvelopeFields() {
		assertThrows(NullPointerException.class, () -> new CommandId(null));
		assertThrows(NullPointerException.class, () -> new PocomaUserId(null));
		assertThrows(NullPointerException.class, () -> new CommandType(null));
		assertThrows(IllegalArgumentException.class, () -> new CommandType(" "));
		assertThrows(IllegalArgumentException.class, () -> new RecordedCommand(
				new CommandId(UUID.randomUUID()), new CommandType("TYPE_V1"), " ", NOW, authorization()));
	}

	@Test
	void validatesProvenanceArtifactsAndTypedEventResults() {
		CommandExecutionInput subject = new CommandExecutionInput("POT", "pot-1", 7);
		CommandExecutionArtifact artifact = new CommandExecutionArtifact(
				"EVENT", "PotUpdated", "event-1", OptionalLong.empty(), Optional.of(subject), NOW);
		TestBusinessEvent event = new TestBusinessEvent("updated");
		List<BusinessEvent> mutableEvents = new ArrayList<>(List.of(event));
		CommandUseCaseResult.Succeeded succeeded = new CommandUseCaseResult.Succeeded(List.of(subject), mutableEvents);
		mutableEvents.clear();

		assertSame(event, succeeded.events().getFirst());
		assertThrows(UnsupportedOperationException.class,
				() -> succeeded.events().add(new TestBusinessEvent("deleted")));
		assertThrows(NullPointerException.class,
				() -> new CommandUseCaseResult.Succeeded(List.of(), Collections.singletonList(null)));
		assertEquals(Optional.of(subject), artifact.subject());
		assertThrows(IllegalArgumentException.class, () -> new CommandExecutionInput("POT", "pot-1", 0));
		assertThrows(IllegalArgumentException.class, () -> new CommandExecutionArtifact(
				"EVENT", "TYPE", "id", OptionalLong.of(0), Optional.empty(), NOW));
	}

	private static AuthorizationSnapshot authorization() {
		return new AuthorizationSnapshot(new PocomaUserId(UUID.randomUUID()), Set.of(), NOW, NOW,
				NOW.plusSeconds(60), "issuer");
	}

	private record TestBusinessEvent(String change) implements BusinessEvent {}
}
