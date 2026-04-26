package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class AddPotShareholdersAuthorizationPolicyTest {

	private final AddPotShareholdersAuthorizationPolicy policy = new AddPotShareholdersAuthorizationPolicy();

	@Test
	void allowsCreatorToAddPotShareholders() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanAddPotShareholders(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanAddPotShareholders(UUID.randomUUID().toString(), creatorId));

		assertEquals("POT_SHAREHOLDERS_ADD_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsAnonymousUser() {
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanAddPotShareholders(null, UserId.of(UUID.randomUUID())));

		assertEquals("POT_SHAREHOLDERS_ADD_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanAddPotShareholders("user-id", null));
	}
}
