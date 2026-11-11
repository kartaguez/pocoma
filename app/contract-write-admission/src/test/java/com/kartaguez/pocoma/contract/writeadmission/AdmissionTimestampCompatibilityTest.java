package com.kartaguez.pocoma.contract.writeadmission;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** The removed JDBC validation remains an independent test oracle, outside production contracts. */
class AdmissionTimestampCompatibilityTest {
    @Test void legacyRoundTripPrecisionAndRejectionsArePreserved() {
        long minimum = 946684800L - 211813488000L;
        long maximum = 946684800L + 9223371331200L;
        for (long second : new long[] {Long.MIN_VALUE / 1000L - 1, Long.MIN_VALUE / 1000L,
                minimum - 1, minimum, -1, 0, 1, 1794355200L, maximum - 1, maximum,
                Long.MAX_VALUE / 1000L, Long.MAX_VALUE / 1000L + 1}) {
            for (int nano : new int[] {0, 1, 999, 1000, 123456000, 999999000, 999999999}) {
                var instant = Instant.ofEpochSecond(second, nano);
                boolean legacy;
                try {
                    legacy = nano % 1000 == 0 && Timestamp.from(instant).toInstant().equals(instant);
                    new PotCommand.CreateInvitation(instant);
                } catch (RuntimeException rejected) { legacy = false; }
                var input = Map.<String,Object>of("operation", "CreateInvitation",
                        "potId", new UUID(0,1).toString(), "observedVersion", 0,
                        "bindingToken", "fixture", "payload", Map.of("expiresAt", instant.toString()));
                if (legacy) {
                    var decoded = CommandAdmissionInput.decode(input, new PotCommand.Eui("issuer","subject"),1024);
                    assertEquals(instant, ((PotCommand.CreateInvitation)decoded.command(new UUID(0,2)).intent()).expiresAt());
                } else assertThrows(AdmissionFailure.class,
                        () -> CommandAdmissionInput.decode(input, new PotCommand.Eui("issuer","subject"),1024), instant.toString());
            }
        }
    }
}
