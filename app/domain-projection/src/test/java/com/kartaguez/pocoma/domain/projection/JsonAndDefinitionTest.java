package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonAndDefinitionTest {
	@Test
	void jsonContainersDefensivelyCopyTheirContents() {
		var objectSource = new HashMap<String, JsonValue>();
		objectSource.put("name", new JsonString("Pocoma"));
		var object = new JsonObject(objectSource);
		objectSource.clear();
		assertEquals(Map.of("name", new JsonString("Pocoma")), object.values());
		assertThrows(UnsupportedOperationException.class,
				() -> object.values().put("other", JsonNull.INSTANCE));

		var arraySource = new ArrayList<JsonValue>();
		arraySource.add(new JsonNumber(new BigDecimal("12.50")));
		var array = new JsonArray(arraySource);
		arraySource.clear();
		assertEquals(List.of(new JsonNumber(new BigDecimal("12.50"))), array.values());
		assertThrows(UnsupportedOperationException.class, () -> array.values().clear());
	}

	@Test
	void jsonContainersRejectJavaNullButAcceptTheJsonNullSingleton() {
		var nullValueMap = new HashMap<String, JsonValue>();
		nullValueMap.put("invalid", null);
		assertThrows(NullPointerException.class, () -> new JsonObject(nullValueMap));
		var nullKeyMap = new HashMap<String, JsonValue>();
		nullKeyMap.put(null, JsonNull.INSTANCE);
		assertThrows(NullPointerException.class, () -> new JsonObject(nullKeyMap));

		var nullArray = new ArrayList<JsonValue>();
		nullArray.add(null);
		assertThrows(NullPointerException.class, () -> new JsonArray(nullArray));
		assertEquals(JsonNull.INSTANCE, new JsonArray(List.of(JsonNull.INSTANCE)).values().getFirst());
		assertThrows(NullPointerException.class, () -> new JsonString(null));
		assertThrows(NullPointerException.class, () -> new JsonNumber(null));
	}

	@Test
	void cardinalitySupportsBoundsAndAnUnboundedMaximum() {
		var bounded = new Cardinality(1, 2);
		assertFalse(bounded.accepts(0));
		assertTrue(bounded.accepts(1));
		assertTrue(bounded.accepts(2));
		assertFalse(bounded.accepts(3));

		var unbounded = new Cardinality(2, null);
		assertFalse(unbounded.accepts(1));
		assertTrue(unbounded.accepts(2));
		assertTrue(unbounded.accepts(Integer.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> new Cardinality(-1, null));
		assertThrows(IllegalArgumentException.class, () -> new Cardinality(2, 1));
	}

	@Test
	void definitionCopiesItsListRejectsNullsAndDuplicateArtifactTypes() {
		var projectionType = new ProjectionType("READ_POT");
		var targetType = new TargetObjectType("POT");
		var artifactType = new ArtifactType("HEADER");
		var definition = new ArtifactDefinition(artifactType, new Cardinality(1, 1), JsonNull.INSTANCE);
		var source = new ArrayList<>(List.of(definition));
		var projectionDefinition = new ProjectionDefinition(projectionType, targetType, source);
		source.clear();
		assertEquals(List.of(definition), projectionDefinition.artifactDefinitions());
		assertThrows(UnsupportedOperationException.class,
				() -> projectionDefinition.artifactDefinitions().clear());

		assertThrows(IllegalArgumentException.class,
				() -> new ProjectionDefinition(projectionType, targetType, List.of(definition, definition)));
		var definitionsWithNull = new ArrayList<ArtifactDefinition>();
		definitionsWithNull.add(null);
		assertThrows(NullPointerException.class,
				() -> new ProjectionDefinition(projectionType, targetType, definitionsWithNull));
		assertThrows(NullPointerException.class, () -> new ProjectionDefinition(null, targetType, List.of()));
		assertThrows(NullPointerException.class, () -> new ProjectionDefinition(projectionType, null, List.of()));
		assertThrows(NullPointerException.class, () -> new ProjectionDefinition(projectionType, targetType, null));
		assertThrows(NullPointerException.class, () -> new ArtifactDefinition(null, new Cardinality(0, 1), JsonNull.INSTANCE));
		assertThrows(NullPointerException.class, () -> new ArtifactDefinition(artifactType, null, JsonNull.INSTANCE));
		assertThrows(NullPointerException.class, () -> new ArtifactDefinition(artifactType, new Cardinality(0, 1), null));
	}
}
