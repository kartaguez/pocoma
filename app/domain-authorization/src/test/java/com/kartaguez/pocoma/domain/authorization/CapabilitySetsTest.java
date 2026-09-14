package com.kartaguez.pocoma.domain.authorization;

import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.EXPENSE_VIEW;
import static com.kartaguez.pocoma.domain.authorization.PocomaPermissions.VIEW_ARCHIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CapabilitySetsTest {

	@Test
	void copiesTokenCapabilitiesAndChecksEveryRequirement() {
		Set<Permission> source = new HashSet<>(Set.of(EXPENSE_VIEW, VIEW_ARCHIVE));
		TokenCapabilities token = new TokenCapabilities(source);
		source.clear();

		assertTrue(token.containsAll(RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE)));
		assertFalse(TokenCapabilities.of(EXPENSE_VIEW)
				.containsAll(RequiredCurrentCapabilities.of(EXPENSE_VIEW, VIEW_ARCHIVE)));
		assertThrows(UnsupportedOperationException.class, () -> token.permissions().clear());
	}

	@Test
	void composesRequiredCapabilitiesWithoutMutatingTheOriginal() {
		RequiredCurrentCapabilities base = RequiredCurrentCapabilities.of(EXPENSE_VIEW);
		RequiredCurrentCapabilities historical = base.plus(VIEW_ARCHIVE);

		assertEquals(Set.of(EXPENSE_VIEW), base.permissions());
		assertEquals(Set.of(EXPENSE_VIEW, VIEW_ARCHIVE), historical.permissions());
	}

	@Test
	void requiresAtLeastOneCurrentCapability() {
		assertThrows(IllegalArgumentException.class, () -> new RequiredCurrentCapabilities(Set.of()));
	}
}
