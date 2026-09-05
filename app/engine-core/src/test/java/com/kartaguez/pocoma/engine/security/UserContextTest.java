package com.kartaguez.pocoma.engine.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.value.UserId;

class UserContextTest {

	@Test
	void copiesPermissionsAsAnImmutableSet() {
		Permission permission = new Permission("POT", "VIEW");
		Set<Permission> mutable = new HashSet<>(Set.of(permission));
		UserContext context = new UserContext(UserId.of(UUID.randomUUID()), mutable);

		mutable.clear();

		assertEquals(Set.of(permission), context.permissions());
		assertThrows(UnsupportedOperationException.class, () -> context.permissions().clear());
	}

	@Test
	void rejectsNullPermissionsButKeepsAnonymousIdentitySupport() {
		assertThrows(NullPointerException.class, () -> new UserContext(null, null));
		assertNull(new UserContext(null, Set.of()).userId());
	}
}
