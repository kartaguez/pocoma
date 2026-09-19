package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

final class AuthProjectionInterpreter {
	AuthProjection interpret(ValidatedProjection validatedProjection) {
		requireNonNull(validatedProjection, "validatedProjection must not be null");
		ProjectionKey key = validatedProjection.projection().projectionKey();
		try {
			ProjectionArtifact creatorArtifact = validatedProjection.projection().artifacts().stream()
					.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.CREATOR))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException("missing CREATOR artifact"));
			UserId creatorId = userId(creatorArtifact, key);

			var shareholderIdsByUser = new HashMap<UserId, ShareholderId>();
			var usedShareholderIds = new HashSet<ShareholderId>();
			for (ProjectionArtifact artifact : validatedProjection.projection().artifacts()) {
				if (!artifact.artifactType().equals(AuthProjectionDefinition.SHAREHOLDER_USER)) {
					continue;
				}
				var payload = JsonValueReader.object(artifact.payload());
				UserId userId = new UserId(JsonValueReader.uuid(payload, "userId"));
				assertArtifactKey(artifact, userId.value().toString(), key);
				ShareholderId shareholderId = new ShareholderId(JsonValueReader.uuid(payload, "shareholderId"));
				if (!usedShareholderIds.add(shareholderId)) {
					throw violation(key, "shareholderId is linked to more than one user: " + shareholderId);
				}
				shareholderIdsByUser.put(userId, shareholderId);
			}
			return new AuthProjection(creatorId, shareholderIdsByUser);
		}
		catch (AuthProjectionInvariantViolationException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new AuthProjectionInvariantViolationException(key,
					"AUTH projection cannot be interpreted", exception);
		}
	}

	private static UserId userId(ProjectionArtifact artifact, ProjectionKey key) {
		var payload = JsonValueReader.object(artifact.payload());
		UserId userId = new UserId(JsonValueReader.uuid(payload, "userId"));
		assertArtifactKey(artifact, userId.value().toString(), key);
		return userId;
	}

	private static void assertArtifactKey(ProjectionArtifact artifact, String expected, ProjectionKey key) {
		if (!artifact.artifactKey().value().equals(expected)) {
			throw violation(key, artifact.artifactType().value() + " artifact key does not match payload identity");
		}
	}

	private static AuthProjectionInvariantViolationException violation(ProjectionKey key, String message) {
		return new AuthProjectionInvariantViolationException(key, message);
	}
}
