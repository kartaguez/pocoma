package com.kartaguez.pocoma.supra.http.rest.spring.security;

import java.util.UUID;

import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.error.InvalidRequestException;

public final class UserContextFactory {

	public static final String USER_ID_HEADER = "X-User-Id";

	private UserContextFactory() {
	}

	public static UserContext fromHeader(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidRequestException("MISSING_USER_ID", USER_ID_HEADER + " header is required");
		}
		try {
			return new UserContext(UUID.fromString(userId).toString());
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("INVALID_USER_ID", USER_ID_HEADER + " header must be a UUID", exception);
		}
	}

	public static UUID userId(String userId) {
		return UUID.fromString(fromHeader(userId).userId());
	}
}
