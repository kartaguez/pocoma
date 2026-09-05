package com.kartaguez.pocoma.engine.port.out.processing.command.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.security.UserContext;

class RecordedCommandTest {

	@Test
	void freezesActorAndReconstructsBusinessInput() {
		Set<Permission> permissions = new HashSet<>(Set.of(new Permission("POT", "CREATE")));
		UserContext actor = new UserContext(UserId.of(UUID.randomUUID()), permissions);
		CreatePotCommand intent = new CreatePotCommand("Trip", UUID.randomUUID());
		RecordedCommand command = new RecordedCommand(
				UUID.randomUUID(), Optional.empty(), Instant.now(), actor, intent);

		permissions.clear();

		assertEquals(Set.of(new Permission("POT", "CREATE")), command.userContext().permissions());
		assertEquals(command.userContext(), command.toExecuteCommandInput().userContext());
		assertEquals(intent, command.toExecuteCommandInput().commandIntent());
		assertThrows(UnsupportedOperationException.class,
				() -> command.userContext().permissions().add(new Permission("POT", "VIEW")));
	}
}
