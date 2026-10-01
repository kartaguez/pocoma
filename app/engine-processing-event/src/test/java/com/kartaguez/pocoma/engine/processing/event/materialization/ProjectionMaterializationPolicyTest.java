package com.kartaguez.pocoma.engine.processing.event.materialization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class ProjectionMaterializationPolicyTest {
	private static final EventType CREATED = new EventType("CREATED");
	private static final EventType UPDATED = new EventType("UPDATED");
	private static final EventType UNKNOWN = new EventType("UNKNOWN");
	private static final ProjectionType READ = new ProjectionType("READ");
	private static final ProjectionType BALANCES = new ProjectionType("BALANCES");

	@Test
	void requiresDeclaredKeysToEqualTheKnownCatalogue() {
		var missing = assertThrows(IllegalArgumentException.class,
				() -> new ProjectionMaterializationPolicy(Set.of(CREATED, UPDATED), Map.of(CREATED, Set.of(READ))));
		assertTrue(missing.getMessage().contains("missing=[EventType[value=UPDATED]]"));
		assertTrue(missing.getMessage().contains("unknown=[]"));

		var unknown = assertThrows(IllegalArgumentException.class,
				() -> new ProjectionMaterializationPolicy(Set.of(CREATED),
						Map.of(CREATED, Set.of(READ), UNKNOWN, Set.of(BALANCES))));
		assertTrue(unknown.getMessage().contains("missing=[]"));
		assertTrue(unknown.getMessage().contains("unknown=[EventType[value=UNKNOWN]]"));
	}

	@Test
	void acceptsAndPreservesAnExplicitlyEmptyMaterialization() {
		var policy = new ProjectionMaterializationPolicy(Set.of(CREATED, UPDATED),
				Map.of(CREATED, Set.of(READ), UPDATED, Set.of()));

		assertEquals(Set.of(), policy.materializations().get(UPDATED));
		assertEquals(Map.of(CREATED, Set.of(READ)), policy.materializationsFor(Set.of(READ)));
	}

	@Test
	void filtersByServedProjectionTypesAndOmitsEmptyIntersections() {
		var policy = new ProjectionMaterializationPolicy(Set.of(CREATED, UPDATED),
				Map.of(CREATED, Set.of(READ, BALANCES), UPDATED, Set.of(BALANCES)));

		assertEquals(Map.of(CREATED, Set.of(READ)), policy.materializationsFor(Set.of(READ)));
		assertEquals(Map.of(CREATED, Set.of(BALANCES), UPDATED, Set.of(BALANCES)),
				policy.materializationsFor(Set.of(BALANCES)));
		assertEquals(Map.of(), policy.materializationsFor(Set.of(new ProjectionType("OTHER"))));
		assertEquals(Set.of(READ, BALANCES), policy.materializations().get(CREATED));
	}

	@Test
	void deeplyCopiesInputsAndExposesOnlyImmutableCollections() {
		var known = new HashSet<>(Set.of(CREATED));
		var projections = new HashSet<>(Set.of(READ));
		var declared = new HashMap<EventType, Set<ProjectionType>>();
		declared.put(CREATED, projections);
		var policy = new ProjectionMaterializationPolicy(known, declared);

		known.clear();
		projections.add(BALANCES);
		declared.clear();

		assertEquals(Map.of(CREATED, Set.of(READ)), policy.materializations());
		assertThrows(UnsupportedOperationException.class, policy.materializations()::clear);
		assertThrows(UnsupportedOperationException.class, () -> policy.materializations().get(CREATED).add(BALANCES));
		assertThrows(UnsupportedOperationException.class,
				() -> policy.materializationsFor(Set.of(READ)).get(CREATED).clear());
	}

	@Test
	void rejectsNullCollectionsAndElements() {
		assertThrows(NullPointerException.class, () -> new ProjectionMaterializationPolicy(null, Map.of()));
		assertThrows(NullPointerException.class, () -> new ProjectionMaterializationPolicy(Set.of(), null));
		var nullKnownType = new HashSet<EventType>();
		nullKnownType.add(null);
		assertThrows(NullPointerException.class,
				() -> new ProjectionMaterializationPolicy(nullKnownType, Map.of()));

		var nullKey = new HashMap<EventType, Set<ProjectionType>>();
		nullKey.put(null, Set.of());
		assertThrows(NullPointerException.class,
				() -> new ProjectionMaterializationPolicy(Set.of(CREATED), nullKey));

		var nullValue = new HashMap<EventType, Set<ProjectionType>>();
		nullValue.put(CREATED, null);
		assertThrows(NullPointerException.class,
				() -> new ProjectionMaterializationPolicy(Set.of(CREATED), nullValue));
		var nullProjectionType = new HashSet<ProjectionType>();
		nullProjectionType.add(null);
		assertThrows(NullPointerException.class,
				() -> new ProjectionMaterializationPolicy(Set.of(CREATED), Map.of(CREATED, nullProjectionType)));

		var policy = new ProjectionMaterializationPolicy(Set.of(CREATED), Map.of(CREATED, Set.of()));
		assertThrows(NullPointerException.class, () -> policy.materializationsFor(null));
		assertThrows(NullPointerException.class, () -> policy.materializationsFor(nullProjectionType));
	}
}
