package com.kartaguez.pocoma.infra.projection.jsonschema;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonSchemaValidator;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

/** Infrastructure-only JSON Schema boundary. */
public final class NetworkntJsonSchemaValidator implements JsonSchemaValidator {
	private final ObjectMapper objectMapper;
	private final SchemaRegistry schemas;

	public NetworkntJsonSchemaValidator(ObjectMapper objectMapper) {
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
		this.schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
				builder -> builder.schemaRegistryConfig(
						SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
	}

	@Override
	public boolean isValid(JsonValue schema, JsonValue payload) {
		requireNonNull(schema, "schema must not be null");
		requireNonNull(payload, "payload must not be null");
		try {
			return schemas.getSchema(toNode(schema)).validate(toNode(payload)).isEmpty();
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Canonical JSON Schema could not be evaluated", exception);
		}
	}

	private JsonNode toNode(JsonValue value) {
		if (value instanceof JsonObject object) {
			ObjectNode node = objectMapper.createObjectNode();
			object.values().forEach((key, child) -> node.set(key, toNode(child)));
			return node;
		}
		if (value instanceof JsonArray array) {
			ArrayNode node = objectMapper.createArrayNode();
			array.values().forEach(child -> node.add(toNode(child)));
			return node;
		}
		if (value instanceof JsonString string) return objectMapper.getNodeFactory().textNode(string.value());
		if (value instanceof JsonNumber number) return objectMapper.getNodeFactory().numberNode(number.value());
		if (value instanceof JsonBoolean bool) return objectMapper.getNodeFactory().booleanNode(bool.value());
		if (value == JsonNull.INSTANCE) return objectMapper.getNodeFactory().nullNode();
		throw new IllegalStateException("Unknown JsonValue implementation " + value.getClass().getName());
	}
}
