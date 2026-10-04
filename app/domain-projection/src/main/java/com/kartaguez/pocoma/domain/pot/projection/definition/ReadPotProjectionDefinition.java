package com.kartaguez.pocoma.domain.pot.projection.definition;

import java.util.List;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

public final class ReadPotProjectionDefinition {
	public static final ProjectionType PROJECTION_TYPE = new ProjectionType("READ_POT");
	public static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("POT");
	public static final ArtifactType POT = new ArtifactType("POT");
	public static final ArtifactType SHAREHOLDER = new ArtifactType("SHAREHOLDER");
	public static final ArtifactType EXPENSE = new ArtifactType("EXPENSE");
	public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE,
			List.of(new ArtifactDefinition(POT, new Cardinality(1, 1), PotProjectionSchemas.pot()),
					new ArtifactDefinition(SHAREHOLDER, new Cardinality(0, null), PotProjectionSchemas.shareholder()),
					new ArtifactDefinition(EXPENSE, new Cardinality(0, null), PotProjectionSchemas.expense())));
	private ReadPotProjectionDefinition() {}
}
