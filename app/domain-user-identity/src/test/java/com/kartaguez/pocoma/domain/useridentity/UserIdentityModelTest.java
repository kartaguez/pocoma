package com.kartaguez.pocoma.domain.useridentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserIdentityModelTest {

	@Test
	void bindingIdIsAnOpaqueUuidValueWithoutOrdering() {
		UUID value = UUID.fromString("10000000-0000-0000-0000-000000000001");

		BindingId first = new BindingId(value);
		BindingId same = new BindingId(UUID.fromString(value.toString()));
		BindingId other = new BindingId(UUID.fromString("10000000-0000-0000-0000-000000000002"));

		assertEquals(value, first.value());
		assertEquals(first, same);
		assertEquals(first.hashCode(), same.hashCode());
		assertNotEquals(first, other);
		assertFalse(Comparable.class.isAssignableFrom(BindingId.class));
		assertThrows(NullPointerException.class, () -> new BindingId(null));
	}

	@Test
	void externalIdentityPreservesExactIssuerAndSubjectEquality() {
		ExternalIdentity identity = new ExternalIdentity("https://issuer.example/", "Subject-01");

		assertEquals(identity, new ExternalIdentity("https://issuer.example/", "Subject-01"));
		assertNotEquals(identity, new ExternalIdentity("https://issuer.example", "Subject-01"));
		assertNotEquals(identity, new ExternalIdentity("https://issuer.example/", "subject-01"));
		assertThrows(NullPointerException.class, () -> new ExternalIdentity(null, "subject"));
		assertThrows(NullPointerException.class, () -> new ExternalIdentity("issuer", null));
		assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity(" ", "subject"));
		assertThrows(IllegalArgumentException.class, () -> new ExternalIdentity("issuer", " "));
	}

	@Test
	void userContainsOnlyItsCanonicalIdentity() {
		PocomaUserId id = new PocomaUserId(UUID.fromString("20000000-0000-0000-0000-000000000001"));

		assertEquals(id, new User(id).id());
		assertThrows(NullPointerException.class, () -> new User(null));
		assertThrows(NullPointerException.class, () -> new PocomaUserId(null));
	}
}
