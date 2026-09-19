package com.kartaguez.pocoma.infra.read.persistence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNull;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;

final class JsonValueCodec {
	private final ObjectMapper objectMapper = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

	String encode(JsonValue value) {
		try {
			return objectMapper.writeValueAsString(toNode(value));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to encode JsonValue", exception);
		}
	}

	JsonValue decode(String json) {
		try {
			return fromNode(objectMapper.readTree(json));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to decode stored JSON", exception);
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
		if (value instanceof JsonString string) {
			return objectMapper.getNodeFactory().textNode(string.value());
		}
		if (value instanceof JsonNumber number) {
			return objectMapper.getNodeFactory().numberNode(number.value());
		}
		if (value instanceof JsonBoolean bool) {
			return objectMapper.getNodeFactory().booleanNode(bool.value());
		}
		if (value == JsonNull.INSTANCE) {
			return objectMapper.getNodeFactory().nullNode();
		}
		throw new IllegalStateException("Unknown JsonValue implementation: " + value.getClass().getName());
	}

	private JsonValue fromNode(JsonNode node) {
		if (node.isObject()) {
			var values = new LinkedHashMap<String, JsonValue>();
			node.properties().forEach(entry -> values.put(entry.getKey(), fromNode(entry.getValue())));
			return new JsonObject(values);
		}
		if (node.isArray()) {
			var values = new ArrayList<JsonValue>();
			node.forEach(child -> values.add(fromNode(child)));
			return new JsonArray(values);
		}
		if (node.isTextual()) {
			return new JsonString(node.textValue());
		}
		if (node.isNumber()) {
			return new JsonNumber(new BigDecimal(node.asText()));
		}
		if (node.isBoolean()) {
			return new JsonBoolean(node.booleanValue());
		}
		if (node.isNull()) {
			return JsonNull.INSTANCE;
		}
		throw new IllegalStateException("Unsupported stored JSON node: " + node.getNodeType());
	}
}
