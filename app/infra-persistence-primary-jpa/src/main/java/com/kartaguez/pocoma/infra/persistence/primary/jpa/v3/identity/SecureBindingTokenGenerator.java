package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/** Each occurrence uses 256 independent random bits; historical uniqueness is enforced by SQL. */
public final class SecureBindingTokenGenerator {
    private final SecureRandom random;

    public SecureBindingTokenGenerator() {
        this(new SecureRandom());
    }

    public SecureBindingTokenGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random);
    }

    public BindingToken generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new BindingToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
