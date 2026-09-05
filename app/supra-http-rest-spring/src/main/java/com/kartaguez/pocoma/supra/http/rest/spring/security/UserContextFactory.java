package com.kartaguez.pocoma.supra.http.rest.spring.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;
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
			String[] permissionStrings = userScopes.split(USER_SCOPES_DELIMITER);
			Set<Permission> permissions = Arrays.stream(permissionStrings)
					.map(String::trim)
					.filter(s -> !s.isEmpty())
					.map(UserContextFactory::permission)
					.collect(Collectors.toSet());
			return new UserContext(parsedUserId, permissions);
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidRequestException("INVALID_USER_SCOPES", USER_SCOPES_HEADER + " header contains an invalid permission", exception);
		}
	}

	private static Permission permission(String value) {
		String[] parts = value.split(":", -1);
		if (parts.length != 2) {
			throw new IllegalArgumentException("Invalid permission format: " + value);
		}
		return new Permission(
				parts[0].trim().toUpperCase(Locale.ROOT),
				parts[1].trim().toUpperCase(Locale.ROOT));
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
