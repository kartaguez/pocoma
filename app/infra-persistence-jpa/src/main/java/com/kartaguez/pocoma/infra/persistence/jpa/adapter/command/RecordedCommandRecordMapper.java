package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static java.util.Objects.requireNonNull;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.kartaguez.pocoma.engine.command.model.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.TargetCommandEnvelope;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.RecordedCommandRow;

/** Maps the generic Command envelope without interpreting its opaque payload. */
public final class RecordedCommandRecordMapper {
	private final ObjectMapper objectMapper;

	public RecordedCommandRecordMapper(ObjectMapper objectMapper) {
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public String externalAuthoritiesJson(Set<String> authorities) {
		ArrayNode array = objectMapper.createArrayNode();
		requireNonNull(authorities, "authorities must not be null").stream()
				.sorted()
				.forEach(array::add);
		return array.toString();
	}

	public RecordedCommand toDomain(RecordedCommandRow row) {
		requireNonNull(row, "row must not be null");
		var envelope = new TargetCommandEnvelope(
				new ExternalIdentity(row.authIssuer(), row.authSubject()), new BindingId(row.bindingId()),
				new CommandAuthenticationEvidence(
						textValues(row.authExternalAuthoritiesJson(), "external authorities"),
						row.authValidUntil()));
		return new RecordedCommand(new CommandId(row.commandId()), new CommandType(row.commandType()),
				row.payloadJson(), row.submittedAt(), envelope);
	}

	private Set<String> textValues(String json, String description) {
		JsonNode root;
		try {
			root = objectMapper.readTree(requireNonNull(json, description + " JSON must not be null"));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Invalid durable Command " + description + " JSON", exception);
		}
		if (root == null || !root.isArray()) {
			throw new IllegalStateException("Durable Command " + description + " JSON must be an array");
		}
		Set<String> values = new LinkedHashSet<>();
		for (JsonNode item : root) {
			if (!item.isTextual()) {
				throw new IllegalStateException("Each durable Command " + description + " value must be textual");
			}
			values.add(item.textValue());
		}
		return Set.copyOf(values);
	}
}
