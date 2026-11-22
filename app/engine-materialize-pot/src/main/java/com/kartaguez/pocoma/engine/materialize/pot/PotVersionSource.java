package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;

public interface PotVersionSource {
    record EventRef(UUID potId, long version) {
        public EventRef {
            Objects.requireNonNull(potId);
            if (version < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record PotHeaderChange(long localVersion, boolean deleted) {
        public PotHeaderChange {
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record PotDetailsChange(long localVersion, UUID creatorId) {
        public PotDetailsChange {
            Objects.requireNonNull(creatorId);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ShareholderHeaderChange(UUID id, long localVersion, boolean deleted) {
        public ShareholderHeaderChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ShareholderDetailsChange(UUID id, long localVersion, UUID userId) {
        public ShareholderDetailsChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ShareholderWeightChange(UUID id, long localVersion) {
        public ShareholderWeightChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ExpenseHeaderChange(UUID id, long localVersion, boolean deleted) {
        public ExpenseHeaderChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ExpenseDetailsChange(UUID id, long localVersion) {
        public ExpenseDetailsChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record ExpenseFinancialsChange(UUID id, long localVersion) {
        public ExpenseFinancialsChange {
            Objects.requireNonNull(id);
            if (localVersion < 1) throw new IllegalArgumentException("Positive version required");
        }
    }

    record StateDelta(
            Optional<PotHeaderChange> potHeader,
            Optional<PotDetailsChange> potDetails,
            Map<UUID, ShareholderHeaderChange> shareholderHeaders,
            Map<UUID, ShareholderDetailsChange> shareholderDetails,
            Map<UUID, ShareholderWeightChange> shareholderWeights,
            Map<UUID, ExpenseHeaderChange> expenseHeaders,
            Map<UUID, ExpenseDetailsChange> expenseDetails,
            Map<UUID, ExpenseFinancialsChange> expenseFinancials) {
        public StateDelta {
            potHeader = Objects.requireNonNull(potHeader);
            potDetails = Objects.requireNonNull(potDetails);
            shareholderHeaders = Map.copyOf(shareholderHeaders);
            shareholderDetails = Map.copyOf(shareholderDetails);
            shareholderWeights = Map.copyOf(shareholderWeights);
            expenseHeaders = Map.copyOf(expenseHeaders);
            expenseDetails = Map.copyOf(expenseDetails);
            expenseFinancials = Map.copyOf(expenseFinancials);
        }

        public int changedSubobjectCount() {
            return (potHeader.isPresent() ? 1 : 0)
                    + (potDetails.isPresent() ? 1 : 0)
                    + shareholderHeaders.size()
                    + shareholderDetails.size()
                    + shareholderWeights.size()
                    + expenseHeaders.size()
                    + expenseDetails.size()
                    + expenseFinancials.size();
        }

        public boolean isEmpty() {
            return changedSubobjectCount() == 0;
        }
    }

    EventRef eventRef(UUID eventId, RecoveryReadContext context);

    StateDelta stateDelta(
            UUID potId,
            long afterVersionExclusive,
            long targetVersionInclusive,
            RecoveryReadContext context);

    PotVersion composition(UUID eventId, RecoveryReadContext context);

    List<SubobjectVersion> contents(
            PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context);
}
