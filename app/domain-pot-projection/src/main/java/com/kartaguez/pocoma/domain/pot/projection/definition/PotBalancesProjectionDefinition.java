package com.kartaguez.pocoma.domain.pot.projection.definition;

import java.util.List;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

public final class PotBalancesProjectionDefinition {
	public static final ProjectionType PROJECTION_TYPE = new ProjectionType("POT_BALANCES");
	public static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("POT");
	public static final ArtifactType BALANCE = new ArtifactType("BALANCE");
	public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE,
			List.of(new ArtifactDefinition(BALANCE, new Cardinality(0, null), PotProjectionSchemas.balance())));
	private PotBalancesProjectionDefinition() {}
}
