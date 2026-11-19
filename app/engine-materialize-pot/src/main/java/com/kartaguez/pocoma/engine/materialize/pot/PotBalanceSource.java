package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.pot.PotFinancialState;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;

public interface PotBalanceSource {
    record EventRef(UUID potId, long version, boolean potDeleted) {
        public EventRef {
            Objects.requireNonNull(potId, "potId");
            if (version < 1) {
                throw new IllegalArgumentException("Positive version required");
            }
        }
    }

    /**
     * Financial delta on (afterVersionExclusive, targetVersionInclusive].
     * When {@code shareholderDeleted} is true (D4) or {@code changedExpenseCount > maxChangedExpenses}
     * (D5), expense lists are left empty so the caller falls back to the full path without loading
     * participant rows.
     */
    record FinancialDelta(
            boolean shareholderDeleted,
            int changedExpenseCount,
            Map<UUID, Weight> updatedOrAddedWeights,
            List<ExpenseFinancials> removedExpenses,
            List<ExpenseFinancials> addedExpenses) {
        public FinancialDelta {
            if (changedExpenseCount < 0) {
                throw new IllegalArgumentException("Non-negative changedExpenseCount required");
            }
            updatedOrAddedWeights = Map.copyOf(Objects.requireNonNull(updatedOrAddedWeights));
            removedExpenses = List.copyOf(Objects.requireNonNull(removedExpenses));
            addedExpenses = List.copyOf(Objects.requireNonNull(addedExpenses));
        }
    }

    PotFinancialState load(UUID eventId, RecoveryReadContext context);

    EventRef eventRef(UUID eventId, RecoveryReadContext context);

    FinancialDelta financialDelta(
            UUID potId,
            long afterVersionExclusive,
            long targetVersionInclusive,
            int maxChangedExpenses,
            RecoveryReadContext context);
}
