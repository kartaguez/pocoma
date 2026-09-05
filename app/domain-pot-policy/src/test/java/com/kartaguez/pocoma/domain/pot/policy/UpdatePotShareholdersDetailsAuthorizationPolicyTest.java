package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class UpdatePotShareholdersDetailsAuthorizationPolicyTest {

	private final UpdatePotShareholdersDetailsAuthorizationPolicy policy =
			new UpdatePotShareholdersDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersDetails(creatorId, permissions, creatorId, null));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersDetails(UserId.of(UUID.randomUUID()), permissions, creatorId, null));

		assertEquals("POT_SHAREHOLDERS_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "UPDATE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersDetails(UserId.of(UUID.randomUUID()), permissions, null, null));
	}
}
