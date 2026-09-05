package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.RecordedCommandRow;

/** Maps the generic Command envelope without interpreting its opaque payload. */
public final class RecordedCommandRecordMapper {
	private static final Comparator<Permission> PERMISSION_ORDER = Comparator
			.comparing(Permission::objectType).thenComparing(Permission::action);

	private final ObjectMapper objectMapper;

	public RecordedCommandRecordMapper(ObjectMapper objectMapper) {
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public String permissionsJson(Set<Permission> permissions) {
		ArrayNode array = objectMapper.createArrayNode();
		requireNonNull(permissions, "permissions must not be null").stream()
				.sorted(PERMISSION_ORDER)
				.forEach(permission -> array.addObject()
						.put("objectType", permission.objectType())
						.put("action", permission.action()));
		return array.toString();
	}

	public RecordedCommand toDomain(RecordedCommandRow row) {
		requireNonNull(row, "row must not be null");
		AuthorizationSnapshot authorization = new AuthorizationSnapshot(
				new PocomaUserId(row.authUserId()), permissions(row.authPermissionsJson()),
				row.authAuthenticatedAt(), row.authIssuedAt(), row.authValidUntil(), row.authIssuer());
		return new RecordedCommand(new CommandId(row.commandId()), new CommandType(row.commandType()),
				row.payloadJson(), row.submittedAt(), authorization);
	}

	private Set<Permission> permissions(String json) {
		JsonNode root;
		try {
			root = objectMapper.readTree(requireNonNull(json, "authPermissionsJson must not be null"));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid durable Command permissions JSON", exception);
		}
		if (root == null || !root.isArray()) {
			throw new IllegalStateException("Durable Command permissions JSON must be an array");
		}
		Set<Permission> permissions = new LinkedHashSet<>();
		for (JsonNode item : root) {
			if (!item.isObject() || !item.path("objectType").isTextual() || !item.path("action").isTextual()) {
				throw new IllegalStateException("Each durable Command permission must contain textual objectType and action");
			}
			try {
				permissions.add(new Permission(item.get("objectType").textValue(), item.get("action").textValue()));
			} catch (RuntimeException exception) {
				throw new IllegalStateException("Invalid durable Command permission", exception);
			}
		}
		return Set.copyOf(permissions);
	}
}
