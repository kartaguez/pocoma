package com.kartaguez.pocoma.contract.consumption.wakeup;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import java.util.Objects;
import java.util.Optional;

/** Logical wakeup target: (ConsumableFamily, partition). Carries zero business data. */
public record WakeupKey(ConsumableFamily family, int partition) {
    public static final String SUBJECT_PREFIX = "pocoma.v3.wakeup.";
    public static final int DEFAULT_PARTITION_COUNT = 4;

    public WakeupKey {
        Objects.requireNonNull(family, "family");
        if (partition < 0 || partition >= DEFAULT_PARTITION_COUNT) {
            throw new IllegalArgumentException(
                    "partition must be in [0," + (DEFAULT_PARTITION_COUNT - 1) + "]: " + partition);
        }
    }

    public String subject() {
        return SUBJECT_PREFIX + family.name() + "." + partition;
    }

    public int bitIndex() {
        return family.ordinal() * DEFAULT_PARTITION_COUNT + partition;
    }

    public static WakeupKey fromBitIndex(int bitIndex) {
        int famOrd = bitIndex / DEFAULT_PARTITION_COUNT;
        int part = bitIndex % DEFAULT_PARTITION_COUNT;
        return new WakeupKey(ConsumableFamily.values()[famOrd], part);
    }

    public static Optional<WakeupKey> parseSubject(String subject) {
        if (subject == null || !subject.startsWith(SUBJECT_PREFIX)) {
            return Optional.empty();
        }
        String rest = subject.substring(SUBJECT_PREFIX.length());
        int dot = rest.lastIndexOf('.');
        if (dot <= 0 || dot >= rest.length() - 1) {
            return Optional.empty();
        }
        try {
            var fam = ConsumableFamily.valueOf(rest.substring(0, dot));
            int part = Integer.parseInt(rest.substring(dot + 1));
            if (part < 0 || part >= DEFAULT_PARTITION_COUNT) {
                return Optional.empty();
            }
            return Optional.of(new WakeupKey(fam, part));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
