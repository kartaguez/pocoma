package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public record ExpenseState(Versioned<ExpenseHeader> header, Versioned<ExpenseDetails> details, Versioned<ExpenseFinancials> financials) {
    public ExpenseState {
        Objects.requireNonNull(header);
        Objects.requireNonNull(details);
        Objects.requireNonNull(financials);
        if (!header.value().potId().equals(details.value().potId()) || !header.value().expenseId().equals(details.value().expenseId())) throw new InconsistentPotFactsException("Mixed composition");
        if (!header.value().potId().equals(financials.value().potId()) || !header.value().expenseId().equals(financials.value().expenseId())) throw new InconsistentPotFactsException("Mixed composition");
    }
}
