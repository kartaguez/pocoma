package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.BindingRevision;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityAttached;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityDetached;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

class ExternalIdentityBindingFactRecordMapperTest {
	private static final UUID EVENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID BINDING_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final Instant RECORDED_AT = Instant.parse("2026-10-01T10:15:30Z");
	private final ExternalIdentityBindingFactRecordMapper mapper = new ExternalIdentityBindingFactRecordMapper();

	@Test
	void mapsAttachedAndDetachedToTheirExclusiveDurableShapes() {
		ExternalIdentity identity = new ExternalIdentity("issuer", "subject");
		var attached = mapper.toRow(new ExternalIdentityAttached(
				EVENT_ID, identity, new PocomaUserId(USER_ID), new BindingId(BINDING_ID),
				new BindingRevision(1), RECORDED_AT));
		var detached = mapper.toRow(new ExternalIdentityDetached(
				EVENT_ID, identity, new PocomaUserId(USER_ID), new BindingId(BINDING_ID),
				new BindingRevision(2), RECORDED_AT));

		assertEquals("ATTACHED", attached.factType());
		assertEquals(USER_ID, attached.userId());
		assertEquals("issuer", attached.issuer());
		assertEquals("subject", attached.subject());
		assertEquals(1, attached.bindingRevision());
		assertEquals(BINDING_ID, attached.bindingId());
		assertEquals(RECORDED_AT, attached.recordedAt());
		assertEquals("DETACHED", detached.factType());
		assertEquals(USER_ID, detached.userId());
		assertEquals(2, detached.bindingRevision());
	}

	@Test
	void rejectsNullFacts() {
		assertThrows(NullPointerException.class, () -> mapper.toRow(null));
	}
}
