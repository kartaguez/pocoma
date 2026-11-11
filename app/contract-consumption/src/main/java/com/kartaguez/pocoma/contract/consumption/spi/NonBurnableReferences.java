package com.kartaguez.pocoma.contract.consumption.spi;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;

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
