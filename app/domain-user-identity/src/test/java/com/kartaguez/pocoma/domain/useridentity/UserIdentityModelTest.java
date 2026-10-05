package com.kartaguez.pocoma.domain.useridentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
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

	@Test
	void bindingRevisionHasLocalNonNegativeValueSemantics() {
		assertEquals(new BindingRevision(0), new BindingRevision(0));
		assertEquals(new BindingRevision(7), new BindingRevision(7));
		assertNotEquals(new BindingRevision(0), new BindingRevision(1));
		assertThrows(IllegalArgumentException.class, () -> new BindingRevision(-1));
		assertFalse(Comparable.class.isAssignableFrom(BindingRevision.class));
	}

	@Test
	void attachedFactRequiresACompletePositiveRevisionShape() {
		UUID eventId = UUID.fromString("30000000-0000-0000-0000-000000000001");
		ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
		PocomaUserId userId = new PocomaUserId(UUID.fromString("40000000-0000-0000-0000-000000000001"));
		BindingId bindingId = new BindingId(UUID.fromString("50000000-0000-0000-0000-000000000001"));
		Instant recordedAt = Instant.parse("2026-10-01T10:15:30Z");
		ExternalIdentityAttached fact = new ExternalIdentityAttached(
				eventId, identity, userId, bindingId, new BindingRevision(1), recordedAt);

		assertEquals(eventId, fact.eventId());
		assertEquals(identity, fact.externalIdentity());
		assertEquals(userId, fact.userId());
		assertEquals(bindingId, fact.bindingId());
		assertEquals(new BindingRevision(1), fact.bindingRevision());
		assertEquals(recordedAt, fact.recordedAt());
		assertEquals(fact, new ExternalIdentityAttached(
				eventId, identity, userId, bindingId, new BindingRevision(1), recordedAt));
		assertEquals(new BindingRevision(0), new ExternalIdentityAttached(
				eventId, identity, userId, bindingId, new BindingRevision(0), recordedAt).bindingRevision());
		assertAttachedNullRejected(eventId, identity, userId, bindingId, recordedAt);
	}

	@Test
	void detachedFactRequiresACompletePositiveRevisionShapeWithUserId() {
		UUID eventId = UUID.fromString("30000000-0000-0000-0000-000000000002");
		ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
		PocomaUserId userId = new PocomaUserId(UUID.fromString("40000000-0000-0000-0000-000000000002"));
		BindingId bindingId = new BindingId(UUID.fromString("50000000-0000-0000-0000-000000000002"));
		Instant recordedAt = Instant.parse("2026-10-01T10:16:30Z");
		ExternalIdentityDetached fact = new ExternalIdentityDetached(
				eventId, identity, userId, bindingId, new BindingRevision(2), recordedAt);

		assertEquals(eventId, fact.eventId());
		assertEquals(identity, fact.externalIdentity());
		assertEquals(userId, fact.userId());
		assertEquals(bindingId, fact.bindingId());
		assertEquals(new BindingRevision(2), fact.bindingRevision());
		assertEquals(recordedAt, fact.recordedAt());
		assertEquals(fact, new ExternalIdentityDetached(
				eventId, identity, userId, bindingId, new BindingRevision(2), recordedAt));
		assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityDetached(
				eventId, identity, userId, bindingId, new BindingRevision(0), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				null, identity, userId, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				eventId, null, userId, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				eventId, identity, null, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				eventId, identity, userId, null, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				eventId, identity, userId, bindingId, null, recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityDetached(
				eventId, identity, userId, bindingId, new BindingRevision(1), null));
	}

	private static void assertAttachedNullRejected(UUID eventId, ExternalIdentity identity,
			PocomaUserId userId, BindingId bindingId, Instant recordedAt) {
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				null, identity, userId, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				eventId, null, userId, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				eventId, identity, null, bindingId, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				eventId, identity, userId, null, new BindingRevision(1), recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				eventId, identity, userId, bindingId, null, recordedAt));
		assertThrows(NullPointerException.class, () -> new ExternalIdentityAttached(
				eventId, identity, userId, bindingId, new BindingRevision(1), null));
	}
}
