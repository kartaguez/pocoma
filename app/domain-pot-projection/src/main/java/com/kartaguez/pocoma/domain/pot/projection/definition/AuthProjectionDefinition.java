package com.kartaguez.pocoma.domain.pot.projection.definition;

import java.util.List;

import com.kartaguez.pocoma.domain.projection.ArtifactDefinition;
import com.kartaguez.pocoma.domain.projection.ArtifactType;
import com.kartaguez.pocoma.domain.projection.Cardinality;
import com.kartaguez.pocoma.domain.projection.ProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;

public final class AuthProjectionDefinition {
	public static final ProjectionType PROJECTION_TYPE = new ProjectionType("AUTH");
	public static final TargetObjectType TARGET_OBJECT_TYPE = new TargetObjectType("POT");
	public static final ArtifactType CREATOR = new ArtifactType("CREATOR");
	public static final ArtifactType SHAREHOLDER_USER = new ArtifactType("SHAREHOLDER_USER");
	public static final ProjectionDefinition DEFINITION = new ProjectionDefinition(PROJECTION_TYPE, TARGET_OBJECT_TYPE,
			List.of(new ArtifactDefinition(CREATOR, new Cardinality(1, 1), PotProjectionSchemas.creator()),
					new ArtifactDefinition(SHAREHOLDER_USER, new Cardinality(0, null), PotProjectionSchemas.shareholderUser())));
	private AuthProjectionDefinition() {}
}
