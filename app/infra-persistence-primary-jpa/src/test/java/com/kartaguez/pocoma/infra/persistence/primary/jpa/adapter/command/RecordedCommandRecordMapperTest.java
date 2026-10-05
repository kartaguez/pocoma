package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.contracts.command.RecordedCommand;
import com.kartaguez.pocoma.contracts.command.TargetCommandEnvelope;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.command.RecordedCommandRow;

class RecordedCommandRecordMapperTest {
    private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
    private final RecordedCommandRecordMapper mapper = new RecordedCommandRecordMapper(new ObjectMapper());

    @Test
    void serializesExternalAuthoritiesDeterministicallyAndKeepsOpaquePayload() {
        String json = mapper.externalAuthoritiesJson(Set.of("scope:z", "scope:a"));
        assertEquals("[\"scope:a\",\"scope:z\"]", json);
        RecordedCommand command = mapper.toDomain(row("not-json", json));
        assertEquals("not-json", command.serializedPayload());
        assertEquals(Set.of("scope:a", "scope:z"),
                command.envelope().authenticationEvidence().externalAuthorities());
    }

    @Test
    void rejectsMalformedDurableAuthorities() {
        assertEquals(Set.of(), mapper.toDomain(row("", "[]"))
                .envelope().authenticationEvidence().externalAuthorities());
        assertThrows(IllegalStateException.class, () -> mapper.toDomain(row("payload", "not-json")));
        assertThrows(IllegalStateException.class, () -> mapper.toDomain(row("payload", "{}")));
        assertThrows(IllegalStateException.class, () -> mapper.toDomain(row("payload", "[1]")));
    }

    @Test
    void mapsExactIdentityBindingAndProviderNeutralEvidence() {
        UUID bindingId = UUID.randomUUID();
        RecordedCommand command = mapper.toDomain(new RecordedCommandRow(UUID.randomUUID(),
                "POT_CREATE_V1", "payload", NOW, "issuer", "subject", bindingId,
                NOW.plusSeconds(60), "[\"scope:z\",\"scope:a\"]"));
        assertEquals(new TargetCommandEnvelope(new ExternalIdentity("issuer", "subject"),
                new BindingId(bindingId), new CommandAuthenticationEvidence(
                        Set.of("scope:a", "scope:z"), NOW.plusSeconds(60))), command.envelope());
    }

    private static RecordedCommandRow row(String payload, String authorities) {
        return new RecordedCommandRow(UUID.randomUUID(), "POT_CREATE_V1", payload, NOW,
                "issuer", "subject", UUID.randomUUID(), NOW.plusSeconds(60), authorities);
    }
}
