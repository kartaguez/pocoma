package com.kartaguez.pocoma.domain.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BusinessRuleViolationExceptionTest {

	@Test
	void createsBusinessRuleViolationException() {
		BusinessRuleViolationException exception = new BusinessRuleViolationException("rule-code", "Rule message");

		assertEquals("rule-code", exception.ruleCode());
		assertEquals("Rule message", exception.getMessage());
	}

	@Test
	void rejectsNullRuleCode() {
		assertThrows(NullPointerException.class, () -> new BusinessRuleViolationException(null, "Rule message"));
	}

	@Test
	void rejectsNullMessage() {
		assertThrows(NullPointerException.class, () -> new BusinessRuleViolationException("rule-code", null));
	}
}
