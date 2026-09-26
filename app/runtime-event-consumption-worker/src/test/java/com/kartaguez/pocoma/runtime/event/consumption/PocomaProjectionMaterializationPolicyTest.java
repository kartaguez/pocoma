package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;

class PocomaProjectionMaterializationPolicyTest {
	@Test
	void declaresTheTwoCanonicalMaterializationsForAllTenPocomaEventTypes() {
		var policy = PocomaProjectionMaterializationPolicy.policy();
		var expected = Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE);

		assertEquals(10, policy.materializations().size());
		assertEquals(PocomaEventTypes.all(), policy.materializations().keySet());
		policy.materializations().values().forEach(materializations -> assertEquals(expected, materializations));
		assertFalse(policy.materializations().values().stream()
				.anyMatch(materializations -> materializations.contains(AuthProjectionDefinition.PROJECTION_TYPE)));
		assertThrows(UnsupportedOperationException.class, policy.materializations()::clear);
	}

	@Test
	void derivesMaterializationsForReadPotWorkers() {
		assertEquals(expectedFor(ReadPotProjectionDefinition.PROJECTION_TYPE),
				PocomaProjectionMaterializationPolicy.policy()
						.materializationsFor(Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE)));
	}

	@Test
	void derivesMaterializationsForPotBalancesWorkers() {
		assertEquals(expectedFor(PotBalancesProjectionDefinition.PROJECTION_TYPE),
				PocomaProjectionMaterializationPolicy.policy()
						.materializationsFor(Set.of(PotBalancesProjectionDefinition.PROJECTION_TYPE)));
	}

	@Test
	void derivesMaterializationsForWorkersServingBothCanonicalProjectionTypes() {
		var policy = PocomaProjectionMaterializationPolicy.policy();

		assertEquals(policy.materializations(), policy.materializationsFor(Set.of(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE)));
	}

	@Test
	void neverActivatesAuth() {
		assertEquals(Map.of(), PocomaProjectionMaterializationPolicy.policy()
				.materializationsFor(Set.of(AuthProjectionDefinition.PROJECTION_TYPE)));
	}

	private static Map<EventType, Set<ProjectionType>> expectedFor(ProjectionType projectionType) {
		return PocomaEventTypes.all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				eventType -> eventType, eventType -> Set.of(projectionType)));
	}
}
