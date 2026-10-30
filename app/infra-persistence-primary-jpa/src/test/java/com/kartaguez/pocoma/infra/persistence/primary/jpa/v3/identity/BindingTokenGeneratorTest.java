package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.*;

class BindingTokenGeneratorTest {
    @Test
    void l3_generator_contract() {
        var sizes = new ArrayList<Integer>();
        var generator =
                new SecureBindingTokenGenerator(
                        new SecureRandom() {
                            @Override
                            public void nextBytes(byte[] bytes) {
                                sizes.add(bytes.length);
                                for (int i = 0; i < bytes.length; i++)
                                    bytes[i] = (byte) (i + sizes.size());
                            }
                        });
        var first = generator.generate();
        var second = generator.generate();
        assertEquals(List.of(32, 32), sizes);
        assertTrue(first.value().matches("[A-Za-z0-9_-]{43}"));
        byte[] bytes = Base64.getUrlDecoder().decode(first.value());
        assertEquals(32, bytes.length);
        for (int i = 0; i < 32; i++)
            assertTrue(bytes[i] == (byte) (i + 1), "Independent random bytes preserved");
        assertFalse(first.equals(second), "Each generation draws again");
        assertFalse(first.toString().contains(first.value()));
        assertThrows(NullPointerException.class, () -> new SecureBindingTokenGenerator(null));
    }

    @Test
    void l3_real_secure_random_encoding() {
        var generator = new SecureBindingTokenGenerator();
        var seen = new HashSet<String>();
        for (int i = 0; i < 256; i++) {
            var token = generator.generate();
            assertTrue(token.value().matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(token.value()).length);
            assertTrue(seen.add(token.value()), "No repeated sample; this is not an entropy proof");
        }
    }
}
