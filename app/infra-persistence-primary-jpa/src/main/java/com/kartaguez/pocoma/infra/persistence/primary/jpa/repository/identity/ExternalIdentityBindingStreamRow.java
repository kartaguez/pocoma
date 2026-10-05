package com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity;

public record ExternalIdentityBindingStreamRow(String issuer, String subject, long currentRevision) {
}
