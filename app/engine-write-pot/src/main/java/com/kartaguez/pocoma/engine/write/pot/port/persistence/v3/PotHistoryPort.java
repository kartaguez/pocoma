package com.kartaguez.pocoma.engine.write.pot.port.persistence.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.time.Instant;
import java.util.*;

/**
 * All reads use the caller's WRITE snapshot transaction (REPEATABLE_READ or SERIALIZABLE). Unknown
 * Pot is empty; missing/corrupt components of known objects are technical errors. No pagination may
 * be used to construct a negative reference-usage fact.
 */
public interface PotHistoryPort {
    record Selection(
            Set<ShareholderId> shareholders,
            Set<ExpenseId> expenses,
            boolean potDetails,
            boolean shareholderDetails,
            boolean shareholderWeights,
            boolean expenseDetails,
            boolean expenseFinancials) {
        public Selection {
            shareholders = Set.copyOf(shareholders);
            expenses = Set.copyOf(expenses);
        }
    }

    record SelectedShareholder(
            ShareholderReference reference,
            ComponentRead<ShareholderDetails> details,
            ComponentRead<ShareholderWeight> weight) {
        public SelectedShareholder {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(details);
            Objects.requireNonNull(weight);
        }
    }

    record SelectedExpense(
            ComponentRead<ExpenseHeader> header,
            ComponentRead<ExpenseDetails> details,
            ComponentRead<ExpenseFinancials> financials) {
        public SelectedExpense {
            Objects.requireNonNull(header);
            Objects.requireNonNull(details);
            Objects.requireNonNull(financials);
        }
    }

    record SelectedPot(
            PotFacts facts,
            ComponentRead<PotDetails> details,
            Map<ShareholderId, SelectedShareholder> shareholders,
            Map<ExpenseId, SelectedExpense> expenses) {
        public SelectedPot {
            Objects.requireNonNull(facts);
            Objects.requireNonNull(details);
            shareholders = Map.copyOf(shareholders);
            expenses = Map.copyOf(expenses);
        }
    }

    record Snapshot(
            PotId potId,
            long version,
            Instant recordedAt,
            PotState pot,
            Map<ShareholderId, ShareholderState> shareholders,
            Map<ExpenseId, ExpenseState> expenses) {
        public Snapshot {
            Objects.requireNonNull(potId);
            Objects.requireNonNull(recordedAt);
            Objects.requireNonNull(pot);
            if (version < 1 || !potId.equals(pot.header().value().potId()))
                throw new InconsistentPotFactsException("Invalid snapshot identity/version");
            shareholders = Map.copyOf(shareholders);
            expenses = Map.copyOf(expenses);
        }
    }

    /** Complete grouped financial facts at V, including explicit zero-balance Shareholders.
     * No Details, current weights, previous balances or READ access. Caller owns the snapshot. */
    Optional<PotFinancialState> financialAtVersion(PotId potId, long version);

    Optional<Snapshot> atVersion(PotId potId, long version);

    Optional<SelectedPot> current(PotId potId, UserId actor, Selection selection);

    /** Historical grouped contents at the exact V, never authority for a current mutation. */
    Optional<SelectedPot> componentsAtVersion(
            PotId potId, long version, UserId actor, Selection selection);

    ActiveShareholderUsage activeUsage(PotId potId, long version, ShareholderId shareholderId);
}
