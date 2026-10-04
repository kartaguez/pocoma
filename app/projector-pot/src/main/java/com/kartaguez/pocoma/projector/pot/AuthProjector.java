package com.kartaguez.pocoma.projector.pot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

import com.kartaguez.pocoma.domain.projection.pot.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ArtifactKey;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.JsonValue;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.projector.ProjectionProjector;

public final class AuthProjector implements ProjectionProjector<AuthProjectionInput> {
	@Override
	public Projection project(ProjectionKey key, AuthProjectionInput input) {
		if (!key.projectionType().equals(AuthProjectionDefinition.PROJECTION_TYPE)
				|| !key.targetObjectType().equals(AuthProjectionDefinition.TARGET_OBJECT_TYPE)
				|| !key.targetObjectId().value().equals(input.potId().value().toString())
				|| key.targetVersion() != input.version()) {
			throw new IllegalStateException("AUTH input does not match requested key");
		}

		var artifacts = new ArrayList<ProjectionArtifact>();
		String creatorUserId = input.creatorUserId().value().toString();
		artifacts.add(new ProjectionArtifact(AuthProjectionDefinition.CREATOR,
				new ArtifactKey(creatorUserId), object(Map.of("userId", string(creatorUserId)))));
		input.activeShareholders().entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(ShareholderId::value)))
				.forEach(relation -> {
					String shareholderId = relation.getKey().value().toString();
					String userId = relation.getValue().value().toString();
					artifacts.add(new ProjectionArtifact(AuthProjectionDefinition.SHAREHOLDER_USER,
							new ArtifactKey(shareholderId), object(Map.of(
									"shareholderId", string(shareholderId),
									"userId", string(userId)))));
				});
		return new Projection(key, artifacts);
	}

	private static JsonObject object(Map<String, JsonValue> values) { return new JsonObject(values); }
	private static JsonString string(String value) { return new JsonString(value); }
}
