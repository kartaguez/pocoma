package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.kartaguez.pocoma.domain.pot.authorization.PotAuthorizationRelations;
import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.ProjectionArtifact;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ValidatedProjection;

final class AuthProjectionInterpreter {
	PotAuthorizationRelations interpret(ValidatedProjection validatedProjection) {
		requireNonNull(validatedProjection, "validatedProjection must not be null");
		ProjectionKey key = validatedProjection.projection().projectionKey();
		try {
			PotId potId = new PotId(UUID.fromString(key.targetObjectId().value()));
			ProjectionArtifact creator = validatedProjection.projection().artifacts().stream()
					.filter(artifact -> artifact.artifactType().equals(AuthProjectionDefinition.CREATOR))
					.findFirst().orElseThrow(() -> new IllegalArgumentException("missing CREATOR artifact"));
			UserId creatorUserId = new UserId(JsonValueReader.uuid(
					JsonValueReader.object(creator.payload()), "userId"));
			if (!creator.artifactKey().value().equals(creatorUserId.value().toString())) {
				throw new IllegalArgumentException("CREATOR artifact key does not match payload userId");
			}

			Map<ShareholderId, UserId> activeShareholders = new HashMap<>();
			for (ProjectionArtifact artifact : validatedProjection.projection().artifacts()) {
				if (!artifact.artifactType().equals(AuthProjectionDefinition.SHAREHOLDER_USER)) continue;
				var payload = JsonValueReader.object(artifact.payload());
				ShareholderId shareholderId = new ShareholderId(JsonValueReader.uuid(payload, "shareholderId"));
				UserId userId = new UserId(JsonValueReader.uuid(payload, "userId"));
				if (!artifact.artifactKey().value().equals(shareholderId.value().toString())) {
					throw new IllegalArgumentException(
							"SHAREHOLDER_USER artifact key does not match payload shareholderId");
				}
				if (activeShareholders.put(shareholderId, userId) != null) {
					throw new IllegalArgumentException("duplicate SHAREHOLDER_USER artifact");
				}
			}
			return new PotAuthorizationRelations(potId, creatorUserId, activeShareholders);
		}
		catch (RuntimeException exception) {
			if (exception instanceof AuthProjectionInvariantViolationException invariant) throw invariant;
			throw new AuthProjectionInvariantViolationException(key,
					"AUTH projection cannot be interpreted", exception);
		}
	}
}
