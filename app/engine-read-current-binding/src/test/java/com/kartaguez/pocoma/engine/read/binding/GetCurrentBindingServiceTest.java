package com.kartaguez.pocoma.engine.read.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

class GetCurrentBindingServiceTest {
	private static final ExternalIdentity IDENTITY = new ExternalIdentity("issuer", "subject");
	private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

	@Test
	void returnsOnlyAnAttachedBindingForTheExactExternalIdentity() {
		CurrentBinding attached = binding(CurrentBindingStatus.ATTACHED, new PocomaUserId(UUID.randomUUID()));
		var service = new GetCurrentBindingService(port(identity -> {
			assertEquals(IDENTITY, identity);
			return Optional.of(attached);
		}));

		assertEquals(Optional.of(attached), service.getAttached(IDENTITY));
	}

	@Test
	void hidesDetachedAndAbsentBindings() {
		CurrentBinding detached = binding(CurrentBindingStatus.DETACHED, null);
		assertTrue(new GetCurrentBindingService(port(identity -> Optional.of(detached)))
				.getAttached(IDENTITY).isEmpty());
		assertTrue(new GetCurrentBindingService(port(identity -> Optional.empty()))
				.getAttached(IDENTITY).isEmpty());
	}

	@Test
	void issuerAndSubjectHomonymsDoNotSelectAnotherIdentity() {
		var service = new GetCurrentBindingService(port(identity -> identity.equals(IDENTITY)
				? Optional.of(binding(CurrentBindingStatus.ATTACHED, new PocomaUserId(UUID.randomUUID())))
				: Optional.empty()));

		assertTrue(service.getAttached(new ExternalIdentity("other-issuer", "subject")).isEmpty());
		assertTrue(service.getAttached(new ExternalIdentity("issuer", "other-subject")).isEmpty());
	}

	private static CurrentBinding binding(CurrentBindingStatus status, PocomaUserId userId) {
		return new CurrentBinding(IDENTITY, new BindingRevision(7), status, userId,
				status == CurrentBindingStatus.ATTACHED ? new BindingId(UUID.randomUUID()) : null,
				UUID.randomUUID(), NOW);
	}

	private static CurrentBindingProjectionPort port(
			java.util.function.Function<ExternalIdentity, Optional<CurrentBinding>> find) {
		return new CurrentBindingProjectionPort() {
			@Override public CurrentBindingApplyResult apply(CurrentBinding binding) {
				throw new AssertionError("self-service must not write CURRENT_BINDING");
			}
			@Override public Optional<CurrentBinding> find(ExternalIdentity identity) {
				return find.apply(identity);
			}
		};
	}
}
