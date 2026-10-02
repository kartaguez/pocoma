package com.kartaguez.pocoma.engine.command.result;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.JsonArray;
import com.kartaguez.pocoma.domain.projection.JsonBoolean;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

public final class CommandResultProjectionDefinition {
	public static final ProjectionType PROJECTION_TYPE = new ProjectionType("COMMAND_RESULT");
	public static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("COMMAND");
	public static final ArtifactType RESULT = new ArtifactType("COMMAND_RESULT");
	private static final JsonValue STRING = object(Map.of("type", string("string")));
	private static final JsonValue UUID = object(Map.of("type", string("string"), "format", string("uuid")));
	private static final JsonValue NULL = object(Map.of("type", string("null")));
	private static final JsonValue NULLABLE_UUID = object(Map.of("anyOf", array(UUID, NULL)));
	private static final JsonValue NULLABLE_STRING = object(Map.of("anyOf", array(STRING, NULL)));
	private static final JsonValue NULLABLE_VERSION = object(Map.of("anyOf", array(
			object(Map.of("type", string("integer"), "minimum", number(1))), NULL)));
	private static final JsonValue OUTCOME = object(Map.of(
			"type", string("string"),
			"enum", array(string("APPLIED"), string("REJECTED"), string("FAILED"))));
	private static final JsonValue EXTERNAL_IDENTITY = object(Map.of(
			"type", string("object"),
			"properties", object(Map.of("issuer", STRING, "subject", STRING)),
			"required", array(string("issuer"), string("subject")),
			"additionalProperties", new JsonBoolean(false)));
	private static final Map<String, JsonValue> COMMON_PROPERTIES = Map.of(
			"commandId", UUID,
			"outcome", OUTCOME,
			"potId", NULLABLE_UUID,
			"resultingVersion", NULLABLE_VERSION,
			"code", NULLABLE_STRING,
			"resolvedAt", STRING);
	private static final JsonValue SCHEMA = schemaWith(Map.of(
			"visibility", object(Map.of("const", string("EXACT_EXTERNAL_IDENTITY"))),
			"visibleToExternalIdentity", EXTERNAL_IDENTITY),
			List.of("visibility", "visibleToExternalIdentity"));
	private static JsonValue schemaWith(Map<String, JsonValue> visibilityProperties,
			List<String> visibilityRequired) {
		Map<String, JsonValue> properties = new java.util.HashMap<>(COMMON_PROPERTIES);
		properties.putAll(visibilityProperties);
		List<String> required = new java.util.ArrayList<>(List.of(
				"commandId", "outcome", "potId", "resultingVersion", "code", "resolvedAt"));
		required.addAll(visibilityRequired);
		return object(Map.of(
			"type", string("object"),
			"properties", object(Map.copyOf(properties)),
			"required", new JsonArray(required.stream().map(JsonString::new).map(JsonValue.class::cast).toList()),
			"additionalProperties", new JsonBoolean(false)));
	}
	public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(
			PROJECTION_TYPE, TARGET_OBJECT_TYPE,
			List.of(new ArtifactDefinition(RESULT, new Cardinality(1, 1), SCHEMA)));

	private CommandResultProjectionDefinition() {}
	private static JsonObject object(Map<String, JsonValue> values) { return new JsonObject(values); }
	private static JsonArray array(JsonValue... values) { return new JsonArray(List.of(values)); }
	private static JsonString string(String value) { return new JsonString(value); }
	private static JsonNumber number(long value) { return new JsonNumber(BigDecimal.valueOf(value)); }
}
