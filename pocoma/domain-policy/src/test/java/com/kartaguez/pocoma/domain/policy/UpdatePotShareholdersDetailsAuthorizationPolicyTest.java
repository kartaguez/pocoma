package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class UpdatePotShareholdersDetailsAuthorizationPolicyTest {

	private final UpdatePotShareholdersDetailsAuthorizationPolicy policy =
			new UpdatePotShareholdersDetailsAuthorizationPolicy();

	@Test
	void allowsCreatorToUpdatePotShareholdersDetails() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanUpdatePotShareholdersDetails(creatorId.value().toString(), creatorId));
	}

	@Test
	void rejectsAnotherUser() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanUpdatePotShareholdersDetails(UUID.randomUUID().toString(), creatorId));

		assertEquals("POT_SHAREHOLDERS_DETAILS_UPDATE_FORBIDDEN", exception.ruleCode());
	}

	@Test
	void rejectsNullCreatorId() {
		assertThrows(NullPointerException.class, () -> policy.assertCanUpdatePotShareholdersDetails("user-id", null));
	}
}
