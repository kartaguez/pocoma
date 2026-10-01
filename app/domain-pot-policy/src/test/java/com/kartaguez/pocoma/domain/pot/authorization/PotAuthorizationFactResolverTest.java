package com.kartaguez.pocoma.domain.pot.authorization;

import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_CREATOR;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_POT_MEMBER;
import static com.kartaguez.pocoma.domain.pot.authorization.AuthorizationFact.IS_TARGET_SHAREHOLDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

class PotAuthorizationFactResolverTest {

	private final PotAuthorizationFactResolver resolver = new PotAuthorizationFactResolver();

	@Test
	void derivesCreatorMemberAndTargetIdentityFactsFromStructuralRelations() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creator = UserId.of(UUID.randomUUID());
		UserId member = UserId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		PotAuthorizationRelations relations = new PotAuthorizationRelations(
				potId, creator, Map.of(shareholderId, member));

		assertTrue(resolver.resolve(relations, creator, AuthorizationTarget.existing(potId)).contains(IS_POT_CREATOR));
		AuthorizationFacts memberFacts = resolver.resolve(
				relations, member, AuthorizationTarget.existing(shareholderId));
		assertTrue(memberFacts.contains(IS_POT_MEMBER));
		assertTrue(memberFacts.contains(IS_TARGET_SHAREHOLDER));
		assertFalse(resolver.resolve(relations, member, AuthorizationTarget.prospectiveShareholder())
				.contains(IS_TARGET_SHAREHOLDER));
	}

	@Test
	void equivalentCurrentAndHistoricalRelationsProduceIdenticalFacts() {
		PotId potId = PotId.of(UUID.randomUUID());
		UserId creator = UserId.of(UUID.randomUUID());
		UserId member = UserId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		PotAuthorizationRelations current = new PotAuthorizationRelations(potId, creator, Map.of(shareholderId, member));
		PotAuthorizationRelations historical = new PotAuthorizationRelations(potId, creator, Map.of(shareholderId, member));
		AuthorizationTarget target = AuthorizationTarget.existing(shareholderId);

		assertEquals(resolver.resolve(current, member, target), resolver.resolve(historical, member, target));
	}

	@Test
	void rejectsAnExistingPotTargetFromDifferentRelations() {
		PotAuthorizationRelations relations = new PotAuthorizationRelations(
				PotId.of(UUID.randomUUID()), UserId.of(UUID.randomUUID()), Map.of());
		assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
				relations, UserId.of(UUID.randomUUID()), AuthorizationTarget.existing(PotId.of(UUID.randomUUID()))));
	}
}
