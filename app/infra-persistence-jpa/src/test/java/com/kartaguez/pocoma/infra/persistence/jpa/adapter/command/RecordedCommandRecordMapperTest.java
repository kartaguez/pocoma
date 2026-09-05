package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.RecordedCommandRow;

class RecordedCommandRecordMapperTest {
	private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
	private final RecordedCommandRecordMapper mapper = new RecordedCommandRecordMapper(new ObjectMapper());

	@Test
	void serializesPermissionsDeterministicallyAndKeepsFuturePermissions() {
		Set<Permission> permissions = Set.of(
				new Permission("FUTURE_FEATURE", "VIEW"),
				new Permission("EXPENSE", "UPDATE"));

		String json = mapper.permissionsJson(permissions);
		RecordedCommand command = mapper.toDomain(row("not-json", json));

		assertEquals("[{\"objectType\":\"EXPENSE\",\"action\":\"UPDATE\"},"
				+ "{\"objectType\":\"FUTURE_FEATURE\",\"action\":\"VIEW\"}]", json);
		assertEquals(permissions, command.authorization().permissions());
		assertEquals("not-json", command.serializedPayload());
	}

	@Test
	void acceptsEmptyPermissionsButRejectsCorruptedDurablePermissionShapes() {
		assertEquals(Set.of(), mapper.toDomain(row("", "[]")).authorization().permissions());
		assertThrows(IllegalStateException.class, () -> mapper.toDomain(row("payload", "not-json")));
		assertThrows(IllegalStateException.class, () -> mapper.toDomain(row("payload", "{}")));
		assertThrows(IllegalStateException.class,
				() -> mapper.toDomain(row("payload", "[{\"objectType\":\"POT\"}]")));
	}

	private static RecordedCommandRow row(String payload, String permissions) {
		return new RecordedCommandRow(UUID.randomUUID(), "POT_CREATE_V1", payload, NOW,
				UUID.randomUUID(), "issuer", NOW.minusSeconds(2), NOW.minusSeconds(1),
				NOW.plusSeconds(60), permissions);
	}
}
