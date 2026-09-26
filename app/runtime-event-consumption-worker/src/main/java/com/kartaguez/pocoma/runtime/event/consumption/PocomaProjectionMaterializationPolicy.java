package com.kartaguez.pocoma.runtime.event.consumption;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;

import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;

/** Pocoma's explicit EventType to ProjectionType materialization decision table. */
public final class PocomaProjectionMaterializationPolicy {
	private static final Set<ProjectionType> MATERIALIZATIONS = Set.of(
			ReadPotProjectionDefinition.PROJECTION_TYPE,
			PotBalancesProjectionDefinition.PROJECTION_TYPE);
	private static final ProjectionMaterializationPolicy POLICY = new ProjectionMaterializationPolicy(
			PocomaEventTypes.all(), Map.ofEntries(
					entry(PocomaEventTypes.POT_CREATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_DELETED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_ADDED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_WEIGHTS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_CREATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_DELETED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_SHARES_UPDATED, MATERIALIZATIONS)));

	private PocomaProjectionMaterializationPolicy() {}

	public static ProjectionMaterializationPolicy policy() {
		return POLICY;
	}
}
