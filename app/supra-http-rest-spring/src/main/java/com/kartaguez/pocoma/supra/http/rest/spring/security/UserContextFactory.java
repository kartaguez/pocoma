package com.kartaguez.pocoma.supra.http.rest.spring.security;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.security.UserContext;

import com.kartaguez.pocoma.supra.http.rest.spring.error.InvalidRequestException;

public final class UserContextFactory {

	public static final String USER_ID_HEADER = "X-User-Id";
	public static final String USER_SCOPES_HEADER = "X-User-Scopes";
	public static final String USER_SCOPES_DELIMITER = ";";

	private UserContextFactory() {
	}

	public static UserContext fromHeaders(String userId, String userScopes) {
		if (userId == null || userId.isBlank()) {
			throw new InvalidRequestException("MISSING_USER_ID", USER_ID_HEADER + " header is required");
		}
		if (userScopes == null || userScopes.isBlank()) {
			throw new InvalidRequestException("MISSING_USER_SCOPES", USER_SCOPES_HEADER + " header is required");
		}
		UserId parsedUserId = userId(userId);
		try {
			String[] scopeStrings = userScopes.split(USER_SCOPES_DELIMITER);
			Set<Scope> scopeSet = Arrays.stream(scopeStrings)
			 		.map(String::trim)
			 		.filter(s -> !s.isEmpty())
			 		.map(Scope::of)
			 		.collect(Collectors.toSet());
			return new UserContext(parsedUserId, scopeSet);
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("INVALID_USER_SCOPES", USER_SCOPES_HEADER + " header contains an invalid scope", exception);
		}
	}

	public static UserId userId(String userId) {
		try {
			return new UserId(UUID.fromString(userId));
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("INVALID_USER_ID", USER_ID_HEADER + " header must be a UUID", exception);
		}
	}
}
