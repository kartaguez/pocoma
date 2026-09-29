package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.command.model.CommandTerminalEventTypes;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionDefinition;

class PocomaProjectionMaterializationPolicyTest {
	@Test
	void declaresBusinessAndCommandTerminalEventMaterializations() {
		var policy = PocomaProjectionMaterializationPolicy.policy();
		var expected = Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE);

		assertEquals(13, policy.materializations().size());
		assertEquals(java.util.stream.Stream.concat(
				PocomaEventTypes.all().stream(), CommandTerminalEventTypes.all().stream()).collect(
						java.util.stream.Collectors.toUnmodifiableSet()), policy.materializations().keySet());
		PocomaEventTypes.all().forEach(eventType ->
				assertEquals(expected, policy.materializations().get(eventType)));
		CommandTerminalEventTypes.all().forEach(eventType -> assertEquals(
				Set.of(CommandResultProjectionDefinition.PROJECTION_TYPE),
				policy.materializations().get(eventType)));
		assertFalse(policy.materializations().values().stream()
				.anyMatch(materializations -> materializations.contains(new ProjectionType("AUTH"))));
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
		var expected = PocomaEventTypes.all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				eventType -> eventType,
				eventType -> Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE,
						PotBalancesProjectionDefinition.PROJECTION_TYPE)));

		assertEquals(expected, PocomaProjectionMaterializationPolicy.policy().materializationsFor(Set.of(
				ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE)));
	}

	@Test
	void neverActivatesAuth() {
		assertEquals(Map.of(), PocomaProjectionMaterializationPolicy.policy()
				.materializationsFor(Set.of(new ProjectionType("AUTH"))));
	}

	private static Map<EventType, Set<ProjectionType>> expectedFor(ProjectionType projectionType) {
		return PocomaEventTypes.all().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
				eventType -> eventType, eventType -> Set.of(projectionType)));
	}
}
