package com.kartaguez.pocoma.domain.exception;

import java.util.Objects;

public final class BusinessRuleViolationException extends RuntimeException {

	private final String ruleCode;

	public BusinessRuleViolationException(String ruleCode, String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		this.ruleCode = Objects.requireNonNull(ruleCode, "ruleCode must not be null");
	}

	public String ruleCode() {
		return ruleCode;
	}
}
