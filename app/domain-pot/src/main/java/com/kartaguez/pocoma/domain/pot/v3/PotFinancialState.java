package com.kartaguez.pocoma.domain.pot.v3;

import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.util.*;

/** Complete financial composition at one global V. Details and user attachments are irrelevant. */
public record PotFinancialState(
        PotId potId,
        long version,
        Versioned<PotHeader> header,
        Map<ShareholderId, Shareholder> shareholders,
        Map<ExpenseId, Expense> expenses) {
    public record Shareholder(
            Versioned<ShareholderHeader> header, Versioned<ShareholderWeight> weight) {
        public Shareholder {
            Objects.requireNonNull(header);
            Objects.requireNonNull(weight);
        }
    }

    public record Expense(
            Versioned<ExpenseHeader> header, Versioned<ExpenseFinancials> financials) {
        public Expense {
            Objects.requireNonNull(header);
            Objects.requireNonNull(financials);
        }
    }

    public PotFinancialState {
        Objects.requireNonNull(potId);
        Objects.requireNonNull(header);
        shareholders = Map.copyOf(shareholders);
        expenses = Map.copyOf(expenses);
        if (version < 1 || !potId.equals(header.value().potId()) || header.activeFrom() > version)
            throw new InconsistentPotFactsException("Invalid financial Pot/version");
        for (var entry : shareholders.entrySet()) {
            var s = entry.getValue();
            if (!entry.getKey().equals(s.header().value().shareholderId())
                    || !entry.getKey().equals(s.weight().value().shareholderId())
                    || !potId.equals(s.header().value().potId())
                    || !potId.equals(s.weight().value().potId())
                    || s.header().activeFrom() > version
                    || s.weight().activeFrom() > version)
                throw new InconsistentPotFactsException("Mixed financial Shareholder");
        }
        for (var entry : expenses.entrySet()) {
            var e = entry.getValue();
            if (!entry.getKey().equals(e.header().value().expenseId())
                    || !entry.getKey().equals(e.financials().value().expenseId())
                    || !potId.equals(e.header().value().potId())
                    || !potId.equals(e.financials().value().potId())
                    || e.header().activeFrom() > version
                    || e.financials().activeFrom() > version)
                throw new InconsistentPotFactsException("Mixed financial Expense");
            var references = new HashSet<>(e.financials().value().participants().keySet());
            references.add(e.financials().value().payerId());
            for (var id : references) {
                var s = shareholders.get(id);
                if (s == null || (!e.header().value().deleted() && s.header().value().deleted()))
                    throw new InconsistentPotFactsException("Unavailable financial reference");
            }
        }
    }
}
