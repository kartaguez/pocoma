package com.kartaguez.pocoma.engine.projection.balance;

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

public final class PotBalancesProjectionDefinition {
	public static final ProjectionType PROJECTION_TYPE = new ProjectionType("POT_BALANCES");
	public static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("POT");
	public static final ArtifactType BALANCE = new ArtifactType("BALANCE");
	public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE,
			List.of(new ArtifactDefinition(BALANCE, new Cardinality(0, null), balanceSchema())));
	private PotBalancesProjectionDefinition() {}

	private static JsonValue balanceSchema() {
		var integer = new JsonObject(Map.of("type", new JsonString("integer"),
				"minimum", new JsonNumber(BigDecimal.valueOf(Long.MIN_VALUE)),
				"maximum", new JsonNumber(BigDecimal.valueOf(Long.MAX_VALUE))));
		var positive = new JsonObject(Map.of("type", new JsonString("integer"),
				"minimum", new JsonNumber(BigDecimal.ONE),
				"maximum", new JsonNumber(BigDecimal.valueOf(Long.MAX_VALUE))));
		var fraction = strict(Map.of("numerator", integer, "denominator", positive), List.of("numerator", "denominator"));
		var uuid = new JsonObject(Map.of("type", new JsonString("string"), "format", new JsonString("uuid")));
		return strict(Map.of("shareholderId", uuid, "balance", fraction), List.of("shareholderId", "balance"));
	}
	private static JsonObject strict(Map<String, JsonValue> properties, List<String> required) {
		return new JsonObject(Map.of("type", new JsonString("object"), "properties", new JsonObject(properties),
				"required", new JsonArray(required.stream().map(JsonString::new).map(JsonValue.class::cast).toList()),
				"additionalProperties", new JsonBoolean(false)));
	}
}
