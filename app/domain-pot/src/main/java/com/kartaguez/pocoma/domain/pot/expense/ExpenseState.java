package com.kartaguez.pocoma.domain.pot.expense;


import com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.revision.Versioned;
import java.util.Objects;

public record ExpenseState(Versioned<ExpenseHeader> header, Versioned<ExpenseDetails> details, Versioned<ExpenseFinancials> financials) {
    public ExpenseState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        Objects.requireNonNull(financials);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().expenseId().equals(details.value().expenseId())) throw new InconsistentPotFactsException("Mixed composition");
        if (!header.value().potId().equals(financials.value().potId()) || !header.value().expenseId().equals(financials.value().expenseId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
