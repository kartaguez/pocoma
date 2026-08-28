package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class UpdatePotShareholdersWeightsAuthorizationPolicyTest {

	private final UpdatePotShareholdersWeightsAuthorizationPolicy policy =
			new UpdatePotShareholdersWeightsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersWeights() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.WEIGHT, Scope.Action.UPDATE));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersWeights(creatorId, scopes, creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.WEIGHT, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersWeights(UserId.of(UUID.randomUUID()), scopes, creatorId));

		assertEquals("POT_SHAREHOLDERS_WEIGHTS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.WEIGHT, Scope.Action.UPDATE));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersWeights(UserId.of(UUID.randomUUID()), scopes, null));
	}
}
