package com.kartaguez.pocoma.domain.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.UserId;

class ReadPotAuthorizationPolicyTest {

	private final ReadPotAuthorizationPolicy policy = new ReadPotAuthorizationPolicy();

	@Test
	void allowsCreatorToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanReadPot(creatorId.value().toString(), creatorId, false));
	}

	@Test
	void allowsLinkedShareholderToReadPot() {
		UserId creatorId = UserId.of(UUID.randomUUID());

		assertDoesNotThrow(() -> policy.assertCanReadPot(UUID.randomUUID().toString(), creatorId, true));
	}

	@Test
	void rejectsUnlinkedUser() {
		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> policy.assertCanReadPot(UUID.randomUUID().toString(), UserId.of(UUID.randomUUID()), false));

		assertEquals("POT_READ_FORBIDDEN", exception.ruleCode());
	}
}
