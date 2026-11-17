package com.kartaguez.pocoma.contract.consumption.spi;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Dense global publication prefix, read in one source-owned RR read-only snapshot. */
public interface NonBurnableReferences {
    ConsumableFamily family();

    record Reference(long position, UUID eventId, String subjectType) {
        public Reference {
            if (position < 1) throw new IllegalArgumentException("positive position required");
            Objects.requireNonNull(eventId);
            if (Objects.requireNonNull(subjectType).isBlank())
                throw new IllegalArgumentException("subject type required");
        }
    }

    record Window(long afterExclusive, long highInclusive, List<Reference> references) {
        public Window {
            if (afterExclusive < 0 || highInclusive < afterExclusive)
                throw new IllegalArgumentException("invalid global window");
            references = List.copyOf(references);
        }
    }

    Optional<Reference> find(UUID eventId, RecoveryReadContext read);

    final class InvalidReference extends IllegalStateException {
        public InvalidReference(String message) {
            super(message);
        }
    }

    Window window(long afterExclusive, int maxPositions, RecoveryReadContext read);
}
