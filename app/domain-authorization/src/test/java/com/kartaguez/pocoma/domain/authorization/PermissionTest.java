package com.kartaguez.pocoma.domain.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class PermissionTest {

	@Test
	void validatesComponentsAndKeepsExactCase() {
		assertThrows(NullPointerException.class, () -> new Permission(null, "VIEW"));
		assertThrows(IllegalArgumentException.class, () -> new Permission("POT", " "));
		assertNotEquals(new Permission("POT", "VIEW"), new Permission("pot", "VIEW"));
	}

	@Test
	void exposesTheCanonicalCatalogWithoutBalanceArchive() {
		Set<Permission> permissions = Arrays.stream(PocomaPermissions.class.getFields())
				.filter(field -> Modifier.isStatic(field.getModifiers()))
				.filter(field -> field.getType().equals(Permission.class))
				.map(PermissionTest::read)
				.collect(Collectors.toUnmodifiableSet());

		assertEquals(16, permissions.size());
		assertEquals(Set.of("VIEW", "CREATE", "UPDATE", "DELETE", "VIEW_ARCHIVE"),
				actionsFor(permissions, "POT"));
		assertEquals(Set.of("VIEW", "CREATE", "UPDATE", "DELETE", "VIEW_ARCHIVE"),
				actionsFor(permissions, "SHAREHOLDER"));
		assertEquals(Set.of("VIEW", "CREATE", "UPDATE", "DELETE", "VIEW_ARCHIVE"),
				actionsFor(permissions, "EXPENSE"));
		assertEquals(Set.of("VIEW"), actionsFor(permissions, "BALANCE"));
		assertFalse(permissions.contains(new Permission("BALANCE", "VIEW_ARCHIVE")));
	}

	@Test
	void acceptsFuturePermissions() {
		assertEquals(new Permission("FUTURE_FEATURE", "VIEW"),
				new Permission("FUTURE_FEATURE", "VIEW"));
	}

	private static Set<String> actionsFor(Set<Permission> permissions, String objectType) {
		return permissions.stream()
				.filter(permission -> permission.objectType().equals(objectType))
				.map(Permission::action)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Permission read(Field field) {
		try {
			return (Permission) field.get(null);
		}
		catch (IllegalAccessException exception) {
			throw new AssertionError(exception);
		}
	}
}
