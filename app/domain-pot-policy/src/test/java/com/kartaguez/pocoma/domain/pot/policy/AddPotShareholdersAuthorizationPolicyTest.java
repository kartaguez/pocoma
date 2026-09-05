package com.kartaguez.pocoma.domain.pot.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.authorization.Permission;

class AddPotShareholdersAuthorizationPolicyTest {

	private final AddPotShareholdersAuthorizationPolicy policy = new AddPotShareholdersAuthorizationPolicy();

	@Test
	void allowsCreatorToAddPotShareholders() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "CREATE"));
		assertDoesNotThrow(() -> policy.assertCanAddPotShareholders(creatorId, permissions, creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "CREATE"));
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanAddPotShareholders(UserId.of(UUID.randomUUID()), permissions, creatorId));

		assertEquals("POT_SHAREHOLDERS_ADD_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "CREATE"));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanAddPotShareholders(null, permissions, creatorId));

		assertEquals("ANONYMOUS_USER", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		Set<Permission> permissions = Set.of(new Permission("SHAREHOLDER", "CREATE"));
		assertThrows(NullPointerException.class, () -> policy.assertCanAddPotShareholders(UserId.of(UUID.randomUUID()), permissions, null));
	}
}
