package com.kartaguez.pocoma.engine.projection.pot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.projection.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;

class AuthProjectorTest {
	private static final PotId POT_ID = PotId.of(UUID.fromString("10000000-0000-0000-0000-000000000001"));
	private static final UserId CREATOR = UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000001"));
	private static final UserId MEMBER = UserId.of(UUID.fromString("20000000-0000-0000-0000-000000000002"));
	private static final ShareholderId SHAREHOLDER_A = ShareholderId.of(
			UUID.fromString("30000000-0000-0000-0000-000000000001"));
	private static final ShareholderId SHAREHOLDER_B = ShareholderId.of(
			UUID.fromString("30000000-0000-0000-0000-000000000002"));

	@Test
	void producesCreatorAndOneRelationPerShareholderInCanonicalOrder() {
		var projection = new AuthProjector().project(key(7), new AuthProjectionInput(POT_ID, 7, CREATOR,
				Map.of(SHAREHOLDER_B, MEMBER, SHAREHOLDER_A, MEMBER)));

		assertEquals(key(7), projection.projectionKey());
		assertEquals(List.of("CREATOR", "SHAREHOLDER_USER", "SHAREHOLDER_USER"), projection.artifacts().stream()
				.map(artifact -> artifact.artifactType().value()).toList());
		assertEquals(List.of(CREATOR.value().toString(), SHAREHOLDER_A.value().toString(),
				SHAREHOLDER_B.value().toString()), projection.artifacts().stream()
				.map(artifact -> artifact.artifactKey().value()).toList());
		assertEquals(new JsonString(CREATOR.value().toString()),
				((JsonObject) projection.artifacts().getFirst().payload()).values().get("userId"));
		projection.artifacts().subList(1, 3).forEach(artifact -> assertEquals(
				new JsonString(MEMBER.value().toString()), ((JsonObject) artifact.payload()).values().get("userId")));
	}

	@Test
	void keepsCreatorAndMembershipIndependentAndAllowsNoMember() {
		var noMember = new AuthProjector().project(key(1),
				new AuthProjectionInput(POT_ID, 1, CREATOR, Map.of()));
		var creatorMember = new AuthProjector().project(key(2),
				new AuthProjectionInput(POT_ID, 2, CREATOR, Map.of(SHAREHOLDER_A, CREATOR)));

		assertEquals(1, noMember.artifacts().size());
		assertEquals(2, creatorMember.artifacts().size());
		assertEquals(CREATOR.value().toString(), creatorMember.artifacts().get(0).artifactKey().value());
		assertEquals(SHAREHOLDER_A.value().toString(), creatorMember.artifacts().get(1).artifactKey().value());
	}

	@Test
	void rejectsAnInputForAnotherProjectionKey() {
		var input = new AuthProjectionInput(POT_ID, 7, CREATOR, Map.of());
		assertThrows(IllegalStateException.class, () -> new AuthProjector().project(key(8), input));
	}

	private static ProjectionKey key(long version) {
		return new ProjectionKey(AuthProjectionDefinition.PROJECTION_TYPE,
				AuthProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(POT_ID.value().toString()), version);
	}
}
