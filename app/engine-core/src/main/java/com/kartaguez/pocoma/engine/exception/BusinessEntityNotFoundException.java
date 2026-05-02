package com.kartaguez.pocoma.engine.exception;

import java.util.Objects;

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
