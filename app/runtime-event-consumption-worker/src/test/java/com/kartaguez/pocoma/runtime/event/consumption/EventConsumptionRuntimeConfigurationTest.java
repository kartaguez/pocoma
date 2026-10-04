package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;

import com.kartaguez.pocoma.engine.produce.projectiontask.materialization.PocomaProjectionMaterializationPolicy;
import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.pot.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;

class EventConsumptionRuntimeConfigurationTest {
	@Test
	void acceptsOnlyExplicitProjectionTypesMaterializableByTheCanonicalPolicy() {
		var policy = PocomaProjectionMaterializationPolicy.policy();

		assertEquals(Set.of(ReadPotProjectionDefinition.PROJECTION_TYPE),
				EventConsumptionRuntimeConfiguration.projectionTypes(List.of("READ_POT"), policy));
		assertEquals(Set.of(AuthProjectionDefinition.PROJECTION_TYPE, ReadPotProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.PROJECTION_TYPE),
				EventConsumptionRuntimeConfiguration.projectionTypes(
						List.of("AUTH", "READ_POT", "POT_BALANCES"), policy));
	}

	@Test
	void rejectsMissingEmptyDuplicateAndNonMaterializableProjectionTypes() {
		var policy = PocomaProjectionMaterializationPolicy.policy();

		assertThrows(IllegalStateException.class,
				() -> EventConsumptionRuntimeConfiguration.projectionTypes(null, policy));
		assertThrows(IllegalStateException.class,
				() -> EventConsumptionRuntimeConfiguration.projectionTypes(List.of(), policy));
		assertThrows(IllegalStateException.class,
				() -> EventConsumptionRuntimeConfiguration.projectionTypes(List.of(" "), policy));
		assertThrows(IllegalStateException.class,
				() -> EventConsumptionRuntimeConfiguration.projectionTypes(
						List.of("READ_POT", "READ_POT"), policy));
		assertThrows(IllegalStateException.class,
				() -> EventConsumptionRuntimeConfiguration.projectionTypes(
						List.of("NOT_MATERIALIZABLE"), policy));
	}
}
