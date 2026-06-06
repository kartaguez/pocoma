package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.value.UserId;

class UpdatePotShareholdersDetailsAuthorizationPolicyTest {

	private final UpdatePotShareholdersDetailsAuthorizationPolicy policy =
			new UpdatePotShareholdersDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersDetails(creatorId, scopes, creatorId, null));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersDetails(UserId.of(UUID.randomUUID()), scopes, creatorId, null));

		assertEquals("POT_SHAREHOLDERS_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Scope> scopes = Set.of(new Scope(Scope.Resource.SHAREHOLDER, Scope.SubResource.DETAILS, Scope.Action.UPDATE));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersDetails(UserId.of(UUID.randomUUID()), scopes, null, null));
	}
}
