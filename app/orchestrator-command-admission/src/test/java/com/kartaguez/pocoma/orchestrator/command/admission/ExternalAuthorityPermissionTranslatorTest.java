package com.kartaguez.pocoma.orchestrator.command.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.authorization.Permission;

class ExternalAuthorityPermissionTranslatorTest {
	private final ExternalAuthorityPermissionTranslator translator = new ExternalAuthorityPermissionTranslator();

	@Test
	void translatesKnownAndFuturePocomaAuthoritiesWithoutEscalation() {
		assertEquals(Set.of(
				new Permission("POT", "UPDATE"),
				new Permission("FUTURE_FEATURE", "VIEW")),
				translator.translate(Set.of(
						"pocoma:pot:update", "pocoma:future_feature:view",
						"openid", "realm:admin", "pocoma:pot", "pocoma:POT:delete")));
	}

	@Test
	void emptyAuthoritiesProduceNoPermissions() {
		assertEquals(Set.of(), translator.translate(Set.of()));
	}
}
