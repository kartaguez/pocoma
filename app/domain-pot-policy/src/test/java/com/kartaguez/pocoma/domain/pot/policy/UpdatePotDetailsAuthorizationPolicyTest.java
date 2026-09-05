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

class UpdatePotDetailsAuthorizationPolicyTest {

	private final UpdatePotDetailsAuthorizationPolicy policy = new UpdatePotDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "UPDATE"));

		assertDoesNotThrow(() -> policy.assertCanUpdatePotDetails(creatorId, permissions, creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "UPDATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(UserId.of(UUID.randomUUID()), permissions, creatorId));

		assertEquals("POT_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		Set<Permission> permissions = Set.of(new Permission("POT", "UPDATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotDetails(null, permissions, UserId.of(UUID.randomUUID())));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("POT", "UPDATE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotDetails(UserId.of(UUID.randomUUID()), permissions, null));
	}
}
