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

class ReadPotAuthorizationPolicyTest {

	private final ReadPotAuthorizationPolicy policy = new ReadPotAuthorizationPolicy();

	@Test
	void allowsCreatorToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "VIEW"));

		assertDoesNotThrow(() -> policy.assertCanReadPot(creatorId, permissions, creatorId, Set.of()));
	}

	@Test
	void allowsLinkedShareholderToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		UserId shareholderId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("POT", "VIEW"));

		assertDoesNotThrow(() -> policy.assertCanReadPot(shareholderId, permissions, creatorId, Set.of(shareholderId)));
	}

	@Test
	void rejectsUnlinkedUser() {
		Set<Permission> permissions = Set.of(new Permission("POT", "VIEW"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanReadPot(UserId.of(UUID.randomUUID()), permissions, UserId.of(UUID.randomUUID()), Set.of()));

		assertEquals("POT_READ_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void allowsAuthenticatedUserToListReadablePots() {
		Set<Permission> permissions = Set.of(new Permission("POT", "VIEW"));

		assertDoesNotThrow(() -> policy.assertCanListReadablePots(UserId.of(UUID.randomUUID()), permissions));
	}

	@Test
	void rejectsAnonymousUserListingReadablePots() {
		Set<Permission> permissions = Set.of(new Permission("POT", "VIEW"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanListReadablePots(null, permissions));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsUserWithoutViewPermissionWhenListingReadablePots() {
		Set<Permission> permissions = Set.of(new Permission("POT", "CREATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanListReadablePots(UserId.of(UUID.randomUUID()), permissions));

		assertEquals("MISSING_PERMISSION", exception.ruleCode());
	}
}
