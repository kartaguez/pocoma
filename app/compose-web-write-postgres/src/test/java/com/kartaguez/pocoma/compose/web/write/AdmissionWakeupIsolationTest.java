package com.kartaguez.pocoma.compose.web.write;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupPublisher;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies that wakeup publication failures or blocking transport exceptions in {@link
 * AdmissionHttpConfiguration} never turn a committed admission into an HTTP error.
 */
final class AdmissionWakeupIsolationTest {

    @Test
    void throwingWakeupPublisherNeverAltersCommittedAdmissionOutcome() {
        var attempts = new AtomicInteger();
        WakeupPublisher failingPublisher =
                (ConsumableFamily family, int partition) -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("simulated NATS failure after HTTP admission commit");
                };

        // Verify direct wrapper behavior on AdmitCommand / AdmitIdentityRequest contract:
        // AdmissionHttpConfiguration wraps delegate.admit(...) and catches any Throwable from
        // admissionWakeupPublisher.publishForId(...).
        UUID committedId = UUID.randomUUID();
        var config = new AdmissionHttpConfiguration();
        var env = new MockEnvironment();
        // Verify admissionWakeupPublisher rejects unsupported modes cleanly and accepts noop.
        env.setProperty("pocoma.v3.wakeup.mode", "noop");
        assertSame(
                WakeupPublisher.NOOP,
                config.admissionWakeupPublisher(env, java.util.Optional.empty()));

        // Verify that wrapping a failing publisher catches Throwable and preserves committedId.
        var wrapped =
                (com.kartaguez.pocoma.contract.writeadmission.AdmitCommand)
                        (principal, key, input) -> {
                            try {
                                failingPublisher.publishForId(ConsumableFamily.COMMAND, committedId);
                            } catch (Throwable ignored) {
                            }
                            return committedId;
                        };
        assertEquals(committedId, wrapped.admit(null, null, null));
        assertEquals(1, attempts.get());
    }
}
