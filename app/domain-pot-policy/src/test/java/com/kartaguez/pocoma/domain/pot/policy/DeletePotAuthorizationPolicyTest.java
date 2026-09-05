package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class DeletePotAuthorizationPolicyTest {

	private final DeletePotAuthorizationPolicy policy = new DeletePotAuthorizationPolicy();

	@Test
	void allowsCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "DELETE"));

		policy.assertCanDeletePot(creatorId, permissions, creatorId);
	}

	@Test
	void rejectsUserThatIsNotCreator() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "DELETE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(UserId.of(UUID.randomUUID()), permissions, creatorId));

		assertEquals("POT_DELETE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "DELETE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanDeletePot(null, permissions, creatorId));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("POT", "DELETE"));

		assertThrows(NullPointerException.class, () -> policy.assertCanDeletePot(UserId.of(UUID.randomUUID()), permissions, null));
	}
}
