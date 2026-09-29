package com.kartaguez.pocoma.runtime.event.consumption;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.command.model.CommandTerminalEventTypes;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionDefinition;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;

/** Pocoma's explicit EventType to ProjectionType materialization decision table. */
public final class PocomaProjectionMaterializationPolicy {
	private static final Set<ProjectionType> MATERIALIZATIONS = Set.of(
			AuthProjectionDefinition.PROJECTION_TYPE,
			ReadPotProjectionDefinition.PROJECTION_TYPE,
			PotBalancesProjectionDefinition.PROJECTION_TYPE);
	private static final ProjectionMaterializationPolicy POLICY = new ProjectionMaterializationPolicy(
			Stream.concat(PocomaEventTypes.all().stream(), CommandTerminalEventTypes.all().stream())
					.collect(Collectors.toUnmodifiableSet()), Map.ofEntries(
					entry(PocomaEventTypes.POT_CREATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_DELETED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_ADDED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.POT_SHAREHOLDERS_WEIGHTS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_CREATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_DELETED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_DETAILS_UPDATED, MATERIALIZATIONS),
					entry(PocomaEventTypes.EXPENSE_SHARES_UPDATED, MATERIALIZATIONS),
					entry(CommandTerminalEventTypes.COMMAND_APPLIED,
							Set.of(CommandResultProjectionDefinition.PROJECTION_TYPE)),
					entry(CommandTerminalEventTypes.COMMAND_REJECTED,
							Set.of(CommandResultProjectionDefinition.PROJECTION_TYPE)),
					entry(CommandTerminalEventTypes.COMMAND_FAILED,
							Set.of(CommandResultProjectionDefinition.PROJECTION_TYPE))));

	private PocomaProjectionMaterializationPolicy() {}

	public static ProjectionMaterializationPolicy policy() {
		return POLICY;
	}
}
