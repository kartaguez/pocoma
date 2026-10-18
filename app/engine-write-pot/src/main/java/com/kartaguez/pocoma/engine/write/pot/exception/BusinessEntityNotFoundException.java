package com.kartaguez.pocoma.engine.write.pot.exception;

import java.util.Objects;

/** Unexpected missing persisted fact after business reference resolution. Never a business rejection. */
public final class BusinessEntityNotFoundException extends RuntimeException {

	private final String entityCode;

	public BusinessEntityNotFoundException(String entityCode, String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		this.entityCode = Objects.requireNonNull(entityCode, "entityCode must not be null");
	}

	public String entityCode() {
		return entityCode;
	}
}
