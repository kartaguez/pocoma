package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.engine.processing.event.materialization.PocomaProjectionMaterializationPolicy;
import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.command.model.CommandTerminalEventTypes;

class PocomaProjectionMaterializationPolicyTest {
	@Test
	void declaresOnlyVersionedBusinessEventMaterializations() {
		var policy = PocomaProjectionMaterializationPolicy.policy();
		var expected = Set.of(AuthProjectionDefinition.PROJECTION_TYPE, ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE);

		assertEquals(PocomaEventTypes.all().size(), policy.materializations().size());
		assertEquals(PocomaEventTypes.all(), policy.materializations().keySet());
		PocomaEventTypes.all().forEach(eventType ->
				assertEquals(expected, policy.materializations().get(eventType)));
		CommandTerminalEventTypes.all().forEach(eventType -> assertEquals(null,
				policy.materializations().get(eventType)));
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
	void derivesDenseMaterializationsForAuthWorkers() {
		assertEquals(expectedFor(AuthProjectionDefinition.PROJECTION_TYPE),
				PocomaProjectionMaterializationPolicy.policy()
						.materializationsFor(Set.of(AuthProjectionDefinition.PROJECTION_TYPE)));
	}

	@Test
	void derivesMaterializationsForWorkersServingAllCanonicalProjectionTypes() {
		var expected = PocomaEventTypes.all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				eventType -> eventType,
				eventType -> Set.of(AuthProjectionDefinition.PROJECTION_TYPE,
						ReadPotProjectionDefinition.PROJECTION_TYPE,
						PotBalancesProjectionDefinition.PROJECTION_TYPE)));

		assertEquals(expected, PocomaProjectionMaterializationPolicy.policy().materializationsFor(Set.of(
				AuthProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE)));
	}

	private static Map<EventType, Set<ProjectionType>> expectedFor(ProjectionType projectionType) {
		return PocomaEventTypes.all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				eventType -> eventType, eventType -> Set.of(projectionType)));
	}
}
