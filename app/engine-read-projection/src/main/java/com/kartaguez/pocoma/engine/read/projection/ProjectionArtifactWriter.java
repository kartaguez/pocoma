package com.kartaguez.pocoma.engine.read.projection;

import com.kartaguez.pocoma.domain.projection.*;

public interface ProjectionArtifactWriter<A> {
	ProjectionContentDigest digest(A artifact);
	void write(ProjectionArtifactId artifactId, ProjectionIdentity identity, A artifact);
	boolean hasSameContent(ProjectionArtifactDescriptor existing, A proposedArtifact);
}
